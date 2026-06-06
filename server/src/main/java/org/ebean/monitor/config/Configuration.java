package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.RequiresProperty;
import io.avaje.jex.staticcontent.StaticContent;
import io.avaje.jsonb.Jsonb;
import io.avaje.metrics.Metrics;
import io.ebean.Database;
import io.ebean.event.ShutdownManager;
import io.ebean.insight.InsightClient;
import org.ebean.monitor.Application;
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
   * <p>
   * When both metrics and plans storage are disabled the server runs as a pure
   * smart-proxy. In that mode we put the DataSource pool offline and skip
   * migrations so the server can start without a Postgres instance.
   */
  @Bean(destroyMethod = "shutdown", destroyPriority=9000)
  Database database() {
    if (Config.getBool("insight.selfReport.enabled", false)) {
      initJvmMetrics();
    }
    // Let the bean-scope lifecycle drive ebean shutdown — avoids double
    // shutdown via ebean's own JVM hook.
    ShutdownManager.deregisterShutdownHook();
    boolean forwardOnly = Application.isForwardOnly();
    return Database.builder()
      .name("db")
      .loadFromProperties()
      .queryPlanCapture(true)
      .runMigration(!forwardOnly)
      .offline(forwardOnly)
      .databasePlatformName(forwardOnly ? "postgres" : null)
      .build();
  }

  /**
   * Initialise JVM metrics — only relevant when self-reporting is enabled.
   */
  private void initJvmMetrics() {
    Metrics
      .jvmMetrics()
      .registerJvmMetrics()
      .registerCGroupMetrics();
  }

  /**
   * Self-reporting client for this server's own metrics.
   */
  @Bean
  @RequiresProperty(value = "insight.selfReport.enabled", equalTo = "true")
  InsightClient withDatabase(Database database) {
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
