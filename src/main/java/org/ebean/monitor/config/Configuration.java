package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.staticcontent.StaticContent;
import io.avaje.jsonb.Jsonb;
import io.avaje.metrics.Metrics;
import io.ebean.Database;
import io.ebean.event.ShutdownManager;
import io.ebean.insight.InsightClient;
import jakarta.inject.Named;

@Factory
class Configuration {

  @Bean @Named
  StaticContent favIcon() {
    return StaticContent.ofClassPath("/static/favicon.ico")
      .route("/favicon.ico")
      .build();
  }

  @Bean @Named
  StaticContent staticContent() {
    return StaticContent.ofClassPath("/static/")
      .route("/static/*")
      .directoryIndex("favicon.ico")
      .build();
  }

  @Bean
  Jsonb jsonb() {
    return Jsonb.builder().build();
  }

  /**
   * Initialise Ebean default database eagerly.
   */
  @Bean(destroyMethod = "shutdown", destroyPriority=9000)
  Database database() {
    initJvmMetrics();
    return Database.builder()
      .name("db")
      .queryPlanCapture(true)
      .runMigration(true)
      .loadFromProperties()
      .build();
  }

  /**
   * Initialise the JVM metrics that we want to monitor.
   */
  private void initJvmMetrics() {
    Metrics
      .jvmMetrics()
      .registerJvmMetrics()
      .registerCGroupMetrics();
  }

  @Bean
  InsightClient withDatabase(Database database) {
    ShutdownManager.deregisterShutdownHook();
    int port = Config.getInt("server.port", 9080);
    return InsightClient.builder()
      .url("http://localhost:" + port)
      .appName("ebean-insight")
      .environment(Config.get("app.environment", "prod"))
      .key("insight")
      .instanceId(null)
      .gzip(false)
      .database(database)
      .capturePlans(true)
      .build();
  }

}
