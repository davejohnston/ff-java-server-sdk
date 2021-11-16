package io.harness.cf.client.api;

import io.harness.cf.ApiClient;
import io.harness.cf.ApiException;
import io.harness.cf.api.ClientApi;
import io.harness.cf.api.MetricsApi;
import io.harness.cf.model.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.Jwts;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public class HarnessConnector implements Connector {

  private final ClientApi api;
  private final MetricsApi metricsApi;
  private final String apiKey;
  private final Config options;
  private final Runnable onUnauthorized;

  private String token;
  private String environment;
  private String cluster;

  public HarnessConnector(
      @NonNull String apiKey, @NonNull Config options, Runnable onUnauthorized) {
    this.apiKey = apiKey;
    this.options = options;
    this.api = new ClientApi(makeApiClient());
    this.metricsApi = new MetricsApi(makeMetricsApiClient());
    this.onUnauthorized = onUnauthorized;
  }

  protected ApiClient makeApiClient() {
    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(options.getConfigUrl());
    apiClient.setConnectTimeout(options.getConnectionTimeout());
    apiClient.setReadTimeout(options.getReadTimeout());
    apiClient.setWriteTimeout(options.getWriteTimeout());
    apiClient.setDebugging(log.isDebugEnabled());
    apiClient.setUserAgent("java " + io.harness.cf.Version.VERSION);
    // if http client response is 403 we need to reauthenticate
    apiClient
        .getHttpClient()
        .newBuilder()
        .addInterceptor(
            chain -> {
              Request request = chain.request();
              // if you need to do something before request replace this
              // comment with code
              Response response = chain.proceed(request);
              if (response.code() == 403) {
                onUnauthorized.run();
              }
              return response;
            })
        .addInterceptor(new RetryInterceptor(3, 2000));
    return apiClient;
  }

  protected ApiClient makeMetricsApiClient() {
    final int maxTimeout = 30 * 60 * 1000;
    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(options.getEventUrl());
    apiClient.setConnectTimeout(maxTimeout);
    apiClient.setReadTimeout(maxTimeout);
    apiClient.setWriteTimeout(maxTimeout);
    apiClient.setDebugging(log.isDebugEnabled());
    apiClient.addDefaultHeader("Authorization", "Bearer " + token);
    apiClient.getHttpClient().newBuilder().addInterceptor(new RetryInterceptor(3, 2000));
    return apiClient;
  }

  @Override
  public Optional<String> authenticate(Consumer<String> onError) {
    try {
      final AuthenticationRequest request = AuthenticationRequest.builder().apiKey(apiKey).build();
      final AuthenticationResponse response = api.authenticate(request);
      token = response.getAuthToken();
      processToken(token);
      return Optional.of(token);
    } catch (ApiException apiException) {
      log.error("Failed to get auth token {}", apiException.getMessage());

      if (apiException.getCode() == 401 || apiException.getCode() == 403) {

        String errorMsg = String.format("Invalid apiKey %s. Serving default value. ", apiKey);

        log.error(errorMsg);
        onError.accept(errorMsg);
      }

      onError.accept(apiException.getMessage());
    }
    return Optional.empty();
  }

  protected void processToken(@NonNull String token) {
    api.getApiClient().addDefaultHeader("Authorization", String.format("Bearer %s", token));

    // get claims
    int i = token.lastIndexOf('.');
    String unsignedJwt = token.substring(0, i + 1);
    Jwt<?, Claims> untrusted = Jwts.parserBuilder().build().parseClaimsJwt(unsignedJwt);

    environment = (String) untrusted.getBody().get("environment");
    cluster = (String) untrusted.getBody().get("clusterIdentifier");
  }

  @Override
  public List<FeatureConfig> getFlags() {
    try {
      return api.getFeatureConfig(environment, cluster);
    } catch (ApiException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public Optional<FeatureConfig> getFlag(@NonNull String identifier) {
    try {
      return Optional.of(api.getFeatureConfigByIdentifier(identifier, environment, cluster));
    } catch (ApiException e) {
      e.printStackTrace();
      return Optional.empty();
    }
  }

  @Override
  public List<Segment> getSegments() {
    try {
      return api.getAllSegments(environment, cluster);
    } catch (ApiException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public Optional<Segment> getSegment(@NonNull String identifier) {
    try {
      return Optional.of(api.getSegmentByIdentifier(identifier, environment, cluster));
    } catch (ApiException e) {
      e.printStackTrace();
      return Optional.empty();
    }
  }

  @Override
  public void postMetrics(Metrics metrics) {
    try {
      metricsApi.postMetrics(environment, cluster, metrics);
    } catch (ApiException e) {
      e.printStackTrace();
    }
  }

  @Override
  public Request stream() {
    final String sseUrl = String.join("", options.getConfigUrl(), "/stream?cluster=" + cluster);

    return new Request.Builder()
        .url(String.format(sseUrl, environment))
        .header("Authorization", "Bearer " + token)
        .header("API-Key", apiKey)
        .build();
  }
}
