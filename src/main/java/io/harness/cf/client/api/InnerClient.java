package io.harness.cf.client.api;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import io.harness.cf.client.dto.Target;
import io.harness.cf.model.FeatureConfig;
import io.harness.cf.model.Variation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class InnerClient
    implements AutoCloseable,
        FlagEvaluateCallback,
        AuthCallback,
        PollerCallback,
        StreamCallback,
        RepositoryCallback,
        MetricsCallback {

  enum Processor {
    POLL,
    STREAM,
    METRICS,
  }

  private Evaluation evaluator;
  private Repository repository;
  private Config options;
  private AuthService authService;
  private PollingProcessor pollProcessor;
  private StreamProcessor streamProcessor;
  private MetricsProcessor metricsProcessor;
  private boolean initialized = false;
  private boolean closing = false;
  private boolean failure = false;
  private boolean pollerReady = false;
  private boolean streamReady = false;
  private boolean metricReady = false;

  private final ConcurrentHashMap<Event, CopyOnWriteArrayList<Consumer<String>>> events =
      new ConcurrentHashMap<>();

  public InnerClient(@NonNull final String sdkKey) {
    this(sdkKey, Config.builder().build());
  }

  public InnerClient(@NonNull final String sdkKey, final Config options) {
    HarnessConnector harnessConnector = new HarnessConnector(sdkKey, options, this::onUnauthorized);
    setUp(sdkKey, harnessConnector, options);
  }

  public InnerClient(@NonNull final String sdkKey, @NonNull Connector connector) {
    this(sdkKey, connector, Config.builder().build());
  }

  public InnerClient(
      @NonNull final String sdkKey, @NonNull Connector connector, final Config options) {
    setUp(sdkKey, connector, options);
  }

  protected void setUp(
      @NonNull final String sdkKey, @NonNull Connector connector, final Config options) {
    if (Strings.isNullOrEmpty(sdkKey)) {
      log.error("SDK key cannot be empty!");
      return;
    }

    this.options = options;

    // initialization
    repository = new StorageRepository(options.getCache(), options.getStore(), this);
    evaluator = new Evaluator(repository);
    authService = new AuthService(connector, options.getPollIntervalInSeconds(), this);
    pollProcessor =
        new PollingProcessor(connector, repository, options.getPollIntervalInSeconds(), this);
    streamProcessor = new StreamProcessor(connector, repository, this);
    metricsProcessor = new MetricsProcessor(connector, this.options, this);

    // start with authentication
    authService.startAsync();
  }

  protected void onUnauthorized() {
    if (closing) {
      return;
    }
    authService.startAsync();
    pollProcessor.stop();
    if (options.isStreamEnabled()) {
      streamProcessor.stop();
    }

    if (options.isAnalyticsEnabled()) {
      metricsProcessor.stop();
    }
  }

  @Override
  public void onAuthSuccess() {
    log.info("SDK successfully logged in");
    if (closing) {
      return;
    }

    // run services only after token is processed
    pollProcessor.start();

    if (options.isStreamEnabled()) {
      streamProcessor.start();
    }

    if (options.isAnalyticsEnabled()) {
      metricsProcessor.start();
    }
  }

  @Override
  public void onAuthError(String error) {
    failure = true;
  }

  @Override
  public void onPollerReady() {
    initialize(Processor.POLL);
  }

  @Override
  public void onPollerError(@NonNull String error) {
    failure = true;
  }

  @Override
  public void onStreamConnected() {
    pollProcessor.stop();
  }

  @Override
  public void onStreamDisconnected() {
    if (!closing) {
      pollProcessor.start();
    }
  }

  @Override
  public void onStreamReady() {
    initialize(Processor.STREAM);
  }

  @Override
  public void onStreamError() {}

  @Override
  public void onFlagStored(@NonNull String identifier) {
    notifyConsumers(Event.CHANGED, identifier);
  }

  @Override
  public void onFlagDeleted(@NonNull String identifier) {
    notifyConsumers(Event.CHANGED, identifier);
  }

  @Override
  public void onSegmentStored(@NonNull String identifier) {
    notifyConsumers(Event.CHANGED, identifier);
  }

  @Override
  public void onSegmentDeleted(@NonNull String identifier) {
    notifyConsumers(Event.CHANGED, identifier);
  }

  @Override
  public void onMetricsReady() {
    initialize(Processor.METRICS);
  }

  @Override
  public void onMetricsError(@NonNull String error) {}

  @Override
  public void onMetricsFailure() {
    failure = true;
  }

  private synchronized void initialize(Processor processor) {
    if (closing) {
      return;
    }
    switch (processor) {
      case POLL:
        pollerReady = true;
        log.debug("PollingProcessor ready");
        break;
      case STREAM:
        streamReady = true;
        log.debug("StreamingProcessor ready");
        break;
      case METRICS:
        metricReady = true;
        log.debug("MetricsProcessor ready");
        break;
    }

    if (options.isStreamEnabled() && !streamReady) {
      return;
    }

    if (options.isAnalyticsEnabled() && !this.metricReady) {
      return;
    }

    if (!this.pollerReady) {
      return;
    }

    initialized = true;
    notify();
    log.info("Initialization is complete");

    notifyConsumers(Event.READY, null);
  }

  protected void notifyConsumers(@NonNull Event event, String value) {
    CopyOnWriteArrayList<Consumer<String>> consumers = events.get(event);
    if (consumers != null && !consumers.isEmpty()) {
      for (Consumer<String> consumer : consumers) {
        consumer.accept(value);
      }
    }
  }

  /** if waitForInitialization is used then on(READY) will never be triggered */
  public synchronized void waitForInitialization() throws InterruptedException {
    if (!initialized) {
      log.info("Wait for initialization to finish");
      wait();
    }
  }

  public void on(@NonNull Event event, @NonNull Consumer<String> consumer) {
    final CopyOnWriteArrayList<Consumer<String>> consumers =
        events.getOrDefault(event, new CopyOnWriteArrayList<>());
    consumers.add(consumer);
    events.put(event, consumers);
  }

  public void off() {
    events.clear();
  }

  public void off(@NonNull Event event) {
    events.get(event).clear();
  }

  public void off(@NonNull Event event, @NonNull Consumer<String> consumer) {
    events.get(event).removeIf(next -> next == consumer);
  }

  public boolean boolVariation(@NonNull String identifier, Target target, boolean defaultValue) {
    return evaluator.boolVariation(identifier, target, defaultValue, this);
  }

  public String stringVariation(
      @NonNull String identifier, Target target, @NonNull String defaultValue) {
    return evaluator.stringVariation(identifier, target, defaultValue, this);
  }

  public double numberVariation(@NonNull String identifier, Target target, double defaultValue) {
    return evaluator.numberVariation(identifier, target, defaultValue, this);
  }

  public JsonObject jsonVariation(
      @NonNull String identifier, Target target, @NonNull JsonObject defaultValue) {
    return evaluator.jsonVariation(identifier, target, defaultValue, this);
  }

  @Override
  public void processEvaluation(
      @NonNull FeatureConfig featureConfig, Target target, @NonNull Variation variation) {
    metricsProcessor.pushToQueue(target, featureConfig, variation);
  }

  public void close() {
    log.info("Closing the client");
    closing = true;
    off();
    authService.close();
    repository.close();
    streamProcessor.close();
    pollProcessor.close();
    metricsProcessor.close();
  }
}
