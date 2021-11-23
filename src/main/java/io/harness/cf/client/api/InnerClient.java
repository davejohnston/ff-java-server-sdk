package io.harness.cf.client.api;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import io.harness.cf.client.connector.Connector;
import io.harness.cf.client.connector.HarnessConnector;
import io.harness.cf.client.connector.Updater;
import io.harness.cf.client.dto.Message;
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
        RepositoryCallback,
        MetricsCallback,
        Updater {

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
  private MetricsProcessor metricsProcessor;
  private UpdateProcessor updateProcessor;
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
    if (Strings.isNullOrEmpty(sdkKey)) {
      log.error("SDK key cannot be empty!");
      return;
    }
    HarnessConnector harnessConnector = new HarnessConnector(sdkKey, this::onUnauthorized);
    setUp(harnessConnector, options);
  }

  public InnerClient(@NonNull final Connector connector) {
    this(connector, Config.builder().build());
  }

  public InnerClient(@NonNull Connector connector, final Config options) {
    setUp(connector, options);
  }

  protected void setUp(@NonNull Connector connector, final Config options) {
    log.info(
        "SDK is not initialized yet! If store is used then values will be loaded from store \n"
            + " otherwise default values will be used in meantime. You can use waitForInitialization method for SDK to be ready.");
    this.options = options;

    // initialization
    repository = new StorageRepository(options.getCache(), options.getStore(), this);
    evaluator = new Evaluator(repository);
    authService = new AuthService(connector, options.getPollIntervalInSeconds(), this);
    pollProcessor =
        new PollingProcessor(connector, repository, options.getPollIntervalInSeconds(), this);
    metricsProcessor = new MetricsProcessor(connector, this.options, this);
    updateProcessor = new UpdateProcessor(connector, this.repository, this);

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
      updateProcessor.stop();
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
      updateProcessor.start();
    }

    if (options.isAnalyticsEnabled()) {
      metricsProcessor.start();
    }
  }

  @Override
  public void onPollerReady() {
    initialize(Processor.POLL);
  }

  @Override
  public void onPollerError(@NonNull String error) {}

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
    repository.findFlagsBySegment(identifier).forEach(s -> notifyConsumers(Event.CHANGED, s));
  }

  @Override
  public void onSegmentDeleted(@NonNull String identifier) {
    repository.findFlagsBySegment(identifier).forEach(s -> notifyConsumers(Event.CHANGED, s));
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
    notify();
  }

  @Override
  public void onConnected() {
    pollProcessor.stop();
  }

  @Override
  public void onDisconnected() {
    if (!closing) {
      pollProcessor.start();
    }
  }

  @Override
  public void onReady() {
    initialize(Processor.STREAM);
  }

  @Override
  public void onError() {
    // on updater error
  }

  @Override
  public void onFailure() {
    failure = true;
    notify();
  }

  @Override
  public void update(@NonNull Message message) {
    updateProcessor.update(message);
  }

  public void update(@NonNull Message message, boolean manual) {
    if (options.isStreamEnabled() && manual) {
      log.warn(
          "You run the update method manually with the stream enabled. Please turn off the stream in this case.");
    }
    update(message);
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
        log.debug("Updater ready");
        break;
      case METRICS:
        metricReady = true;
        log.debug("MetricsProcessor ready");
        break;
    }

    if ((options.isStreamEnabled() && !streamReady)
        || (options.isAnalyticsEnabled() && !this.metricReady)
        || (!this.pollerReady)) {
      return;
    }

    initialized = true;
    notify();
    notifyConsumers(Event.READY, null);
    log.info("Initialization is complete");
  }

  protected void notifyConsumers(@NonNull Event event, String value) {
    events.get(event).forEach(c -> c.accept(value));
  }

  /** if waitForInitialization is used then on(READY) will never be triggered */
  public synchronized void waitForInitialization()
      throws InterruptedException, FeatureFlagInitializeException {
    if (!initialized) {
      log.info("Wait for initialization to finish");
      wait();

      if (failure) {
        throw new FeatureFlagInitializeException();
      }
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
    pollProcessor.close();
    updateProcessor.close();
    metricsProcessor.close();
  }
}