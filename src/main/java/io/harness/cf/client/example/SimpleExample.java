package io.harness.cf.client.example;

import com.google.gson.JsonObject;
import io.harness.cf.client.api.Client;
import io.harness.cf.client.api.Config;
import io.harness.cf.client.api.Event;
import io.harness.cf.client.api.FileMapStore;
import io.harness.cf.client.dto.Target;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SimpleExample {

  private static final String SDK_KEY = "342454eb-baab-48f0-acf4-69cdd93ca14b";
  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public static void main(String... args) throws InterruptedException {

    Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdown));

    final FileMapStore fileStore = new FileMapStore("Non-Freemium");
    final Client client = new Client(SDK_KEY, Config.builder().store(fileStore).build());
    // client.waitForInitialization();
    client.on(
        Event.READY,
        result -> {
          log.info("READY");
        });

    client.on(
        Event.CHANGED,
        result -> {
          log.info("Flag changed {}", result);
        });

    final Target target =
        Target.builder()
            .identifier("target1")
            .isPrivate(false)
            .attribute("testKey", "TestValue")
            .name("target1")
            .build();

    scheduler.scheduleAtFixedRate(
        () -> {
          final boolean bResult = client.boolVariation("test", target, false);
          log.info("Boolean variation: {}", bResult);
          final JsonObject jsonResult = client.jsonVariation("flag4", target, new JsonObject());
          log.info("JSON variation: {}", jsonResult);
        },
        0,
        10,
        TimeUnit.SECONDS);
  }
}
