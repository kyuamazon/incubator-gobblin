package org.apache.gobblin.service;

import java.io.IOException;
import java.util.List;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import com.google.common.collect.Lists;
import com.linkedin.restli.server.resources.BaseResource;

/**
 * Use this class to get who sends the request.
 */
public abstract class RequesterService {

  public static final String REQUESTER_LIST = "gobblin.service.requester.list";

  private static final ObjectMapper objectMapper = new ObjectMapper();

  // user should provide customized logic to get real requester
  public static RequesterService rs = new NoopRequesterService();

  public static List<ServiceRequester> getRequesters(BaseResource resource) {
    return rs.findRequesters(resource);
  }

  public static String serialize(List<ServiceRequester> requestersList) throws IOException {
    String arrayToJson = objectMapper.writeValueAsString(requestersList);
    return arrayToJson;
  }

  public static List<ServiceRequester> deserialize(String serialized) throws IOException {
    TypeReference<List<ServiceRequester>> mapType = new TypeReference<List<ServiceRequester>>() {};
    List<ServiceRequester> jsonToPersonList = objectMapper.readValue(serialized, mapType);
    return jsonToPersonList;
  }

  protected abstract List<ServiceRequester> findRequesters(BaseResource resource);

  /**
   * Default requester service doesn't track who sends the request.
   */
  public static class NoopRequesterService extends RequesterService {

    @Override
    public List<ServiceRequester> findRequesters(BaseResource resource) {
      return Lists.newArrayList();
    }
  }
}
