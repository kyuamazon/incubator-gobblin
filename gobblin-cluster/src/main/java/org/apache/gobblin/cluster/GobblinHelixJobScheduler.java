/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.cluster;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

import org.apache.hadoop.fs.Path;
import org.apache.helix.HelixManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Striped;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.cluster.event.DeleteJobConfigArrivalEvent;
import org.apache.gobblin.cluster.event.NewJobConfigArrivalEvent;
import org.apache.gobblin.cluster.event.UpdateJobConfigArrivalEvent;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.instrumented.StandardMetricsBridge;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.Tag;
import org.apache.gobblin.runtime.JobException;
import org.apache.gobblin.runtime.api.MutableJobCatalog;
import org.apache.gobblin.runtime.listeners.JobListener;
import org.apache.gobblin.scheduler.JobScheduler;
import org.apache.gobblin.scheduler.SchedulerService;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.util.PathUtils;

/**
 * An extension to {@link JobScheduler} that schedules and runs
 * Gobblin jobs on Helix.
 *
 * <p> The actual job running logic is handled by
 * {@link HelixRetriggeringJobCallable}. This callable will first
 * determine if this job should be launched from the same node
 * where the scheduler is running, or from a remote node.
 *
 * <p> If the job should be launched from the scheduler node,
 * {@link GobblinHelixJobLauncher} is invoked. Else the
 * {@link GobblinHelixDistributeJobExecutionLauncher} is invoked.
 *
 * <p> More details can be found at {@link HelixRetriggeringJobCallable}.
 */
@Alpha
public class GobblinHelixJobScheduler extends JobScheduler implements StandardMetricsBridge{

  private static final Logger LOGGER = LoggerFactory.getLogger(GobblinHelixJobScheduler.class);

  private final Properties properties;
  private final HelixManager jobHelixManager;
  private final Optional<HelixManager> taskDriverHelixManager;
  private final EventBus eventBus;
  private final Path appWorkDir;
  private final List<? extends Tag<?>> metadataTags;
  private final ConcurrentHashMap<String, Boolean> jobRunningMap;
  private final MutableJobCatalog jobCatalog;
  private final MetricContext metricContext;

  final GobblinHelixMetrics helixMetrics;
  final GobblinHelixJobSchedulerMetrics jobSchedulerMetrics;
  final GobblinHelixJobLauncherMetrics launcherMetrics;
  final GobblinHelixPlanningJobLauncherMetrics planningJobLauncherMetrics;
  final HelixJobsMapping jobsMapping;
  final Striped<Lock> locks = Striped.lazyWeakLock(Integer.MAX_VALUE);

  private boolean startServicesCompleted;

  public GobblinHelixJobScheduler(Properties properties,
                                  HelixManager jobHelixManager,
                                  Optional<HelixManager> taskDriverHelixManager,
                                  EventBus eventBus,
                                  Path appWorkDir, List<? extends Tag<?>> metadataTags,
                                  SchedulerService schedulerService,
                                  MutableJobCatalog jobCatalog) throws Exception {

    super(properties, schedulerService);
    this.properties = properties;
    this.jobHelixManager = jobHelixManager;
    this.taskDriverHelixManager = taskDriverHelixManager;
    this.eventBus = eventBus;
    this.jobRunningMap = new ConcurrentHashMap<>();
    this.appWorkDir = appWorkDir;
    this.metadataTags = metadataTags;
    this.jobCatalog = jobCatalog;
    this.metricContext = Instrumented.getMetricContext(new org.apache.gobblin.configuration.State(properties), this.getClass());

    int metricsWindowSizeInMin = ConfigUtils.getInt(ConfigUtils.propertiesToConfig(this.properties),
                                                    ConfigurationKeys.METRIC_TIMER_WINDOW_SIZE_IN_MINUTES,
                                                    ConfigurationKeys.DEFAULT_METRIC_TIMER_WINDOW_SIZE_IN_MINUTES);

    this.launcherMetrics = new GobblinHelixJobLauncherMetrics("launcherInScheduler",
                                                              this.metricContext,
                                                              metricsWindowSizeInMin);

    this.jobSchedulerMetrics = new GobblinHelixJobSchedulerMetrics(this.jobExecutor,
                                                                   this.metricContext,
                                                                   metricsWindowSizeInMin);

    this.jobsMapping = new HelixJobsMapping(ConfigUtils.propertiesToConfig(properties),
        PathUtils.getRootPath(appWorkDir).toUri(),
        appWorkDir.toString());

    this.planningJobLauncherMetrics = new GobblinHelixPlanningJobLauncherMetrics("planningLauncherInScheduler",
                                                                          this.metricContext,
                                                                          metricsWindowSizeInMin, this.jobsMapping);

    this.helixMetrics = new GobblinHelixMetrics("helixMetricsInJobScheduler",
                                                  this.metricContext,
                                                  metricsWindowSizeInMin);

    this.startServicesCompleted = false;
  }

  @Override
  public Collection<StandardMetrics> getStandardMetricsCollection() {
    return ImmutableList.of(this.launcherMetrics,
                            this.jobSchedulerMetrics,
                            this.planningJobLauncherMetrics,
                            this.helixMetrics);
  }

  @Override
  protected void startUp() throws Exception {
    this.eventBus.register(this);
    super.startUp();
    this.startServicesCompleted = true;
  }

  @Override
  public void scheduleJob(Properties jobProps, JobListener jobListener) throws JobException {
    try {
      while (!startServicesCompleted) {
        LOGGER.info("{} service is not fully up, waiting here...", this.getClass().getName());
        Thread.sleep(1000);
      }

      scheduleJob(jobProps,
                  jobListener,
                  Maps.newHashMap(),
                  GobblinHelixJob.class);

    } catch (Exception e) {
      throw new JobException("Failed to schedule job " + jobProps.getProperty(ConfigurationKeys.JOB_NAME_KEY), e);
    }
  }

  @Override
  protected void startServices() throws Exception {
  }

  @Override
  public void runJob(Properties jobProps, JobListener jobListener) throws JobException {
    new HelixRetriggeringJobCallable(this,
        this.jobCatalog,
        this.properties,
        jobProps,
        jobListener,
        this.planningJobLauncherMetrics,
        this.helixMetrics,
        this.appWorkDir,
        this.jobHelixManager,
        this.taskDriverHelixManager,
        this.jobsMapping,
        this.locks).call();
  }

  @Override
  public GobblinHelixJobLauncher buildJobLauncher(Properties jobProps)
      throws Exception {
    Properties combinedProps = new Properties();
    combinedProps.putAll(properties);
    combinedProps.putAll(jobProps);

    return new GobblinHelixJobLauncher(combinedProps,
        this.jobHelixManager,
        this.appWorkDir,
        this.metadataTags,
        this.jobRunningMap,
        Optional.of(this.helixMetrics));
  }

  public Future<?> scheduleJobImmediately(Properties jobProps, JobListener jobListener) {
    HelixRetriggeringJobCallable retriggeringJob = new HelixRetriggeringJobCallable(this,
        this.jobCatalog,
        this.properties,
        jobProps,
        jobListener,
        this.planningJobLauncherMetrics,
        this.helixMetrics,
        this.appWorkDir,
        this.jobHelixManager,
        this.taskDriverHelixManager,
        this.jobsMapping,
        this.locks);

    final Future<?> future = this.jobExecutor.submit(retriggeringJob);
    return new Future() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        if (!GobblinHelixJobScheduler.this.isCancelRequested()) {
          return false;
        }
        boolean result = true;
        try {
          retriggeringJob.cancel();
        } catch (JobException e) {
          LOGGER.error("Failed to cancel job " + jobProps.getProperty(ConfigurationKeys.JOB_NAME_KEY), e);
          result = false;
        }
        if (mayInterruptIfRunning) {
          result &= future.cancel(true);
        }
        return result;
      }

      @Override
      public boolean isCancelled() {
        return future.isCancelled();
      }

      @Override
      public boolean isDone() {
        return future.isDone();
      }

      @Override
      public Object get() throws InterruptedException, ExecutionException {
        return future.get();
      }

      @Override
      public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout, unit);
      }
    };
  }

  @Subscribe
  public void handleNewJobConfigArrival(NewJobConfigArrivalEvent newJobArrival) {
    String jobUri = newJobArrival.getJobName();
    LOGGER.info("Received new job configuration of job " + jobUri);
    try {
      Properties jobProps = new Properties();
      jobProps.putAll(newJobArrival.getJobConfig());

      // set uri so that we can delete it later
      jobProps.setProperty(GobblinClusterConfigurationKeys.JOB_SPEC_URI, jobUri);

      this.jobSchedulerMetrics.updateTimeBeforeJobScheduling(jobProps);

      if (jobProps.containsKey(ConfigurationKeys.JOB_SCHEDULE_KEY)) {
        LOGGER.info("Scheduling job " + jobUri);
        scheduleJob(jobProps,
                    new GobblinHelixJobLauncherListener(this.launcherMetrics));
      } else {
        LOGGER.info("No job schedule found, so running job " + jobUri);
        this.jobExecutor.execute(new NonScheduledJobRunner(jobProps,
                                 new GobblinHelixJobLauncherListener(this.launcherMetrics)));
      }
    } catch (JobException je) {
      LOGGER.error("Failed to schedule or run job " + jobUri, je);
    }
  }

  @Subscribe
  public void handleUpdateJobConfigArrival(UpdateJobConfigArrivalEvent updateJobArrival) {
    LOGGER.info("Received update for job configuration of job " + updateJobArrival.getJobName());
    try {
      handleDeleteJobConfigArrival(new DeleteJobConfigArrivalEvent(updateJobArrival.getJobName(),
          updateJobArrival.getJobConfig()));
    } catch (Exception je) {
      LOGGER.error("Failed to update job " + updateJobArrival.getJobName(), je);
    }
    try {
      handleNewJobConfigArrival(new NewJobConfigArrivalEvent(updateJobArrival.getJobName(),
          updateJobArrival.getJobConfig()));
    } catch (Exception je) {
      LOGGER.error("Failed to update job " + updateJobArrival.getJobName(), je);
    }
  }

  @Subscribe
  public void handleDeleteJobConfigArrival(DeleteJobConfigArrivalEvent deleteJobArrival) {
    LOGGER.info("Received delete for job configuration of job " + deleteJobArrival.getJobName());
    try {
      unscheduleJob(deleteJobArrival.getJobName());
    } catch (JobException je) {
      LOGGER.error("Failed to unschedule job " + deleteJobArrival.getJobName());
    }
  }

  /**
   * This class is responsible for running non-scheduled jobs.
   */
  class NonScheduledJobRunner implements Runnable {
    private final Properties jobProps;
    private final GobblinHelixJobLauncherListener jobListener;
    private final Long creationTimeInMillis;

    public NonScheduledJobRunner(Properties jobProps,
                                 GobblinHelixJobLauncherListener jobListener) {

      this.jobProps = jobProps;
      this.jobListener = jobListener;
      this.creationTimeInMillis = System.currentTimeMillis();
    }

    @Override
    public void run() {
      try {
        GobblinHelixJobScheduler.this.jobSchedulerMetrics.updateTimeBeforeJobLaunching(this.jobProps);
        GobblinHelixJobScheduler.this.jobSchedulerMetrics.updateTimeBetweenJobSchedulingAndJobLaunching(this.creationTimeInMillis, System.currentTimeMillis());
        GobblinHelixJobScheduler.this.runJob(this.jobProps, this.jobListener);
      } catch (JobException je) {
        LOGGER.error("Failed to run job " + this.jobProps.getProperty(ConfigurationKeys.JOB_NAME_KEY), je);
      }
    }
  }
}
