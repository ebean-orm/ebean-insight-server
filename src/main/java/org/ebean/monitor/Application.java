package org.ebean.monitor;

import io.avaje.config.Config;
import io.avaje.jex.AvajeJex;

public class Application {

  public static void main(String[] args) {
    configureForwardOnlyIfNeeded();
    AvajeJex.start();
  }

  /**
   * When both metrics and plans storage are disabled the server runs as a pure
   * smart-proxy with no need for Postgres. In that mode we put the DataSource
   * pool offline and skip migrations so the server can start without a DB.
   */
  static void configureForwardOnlyIfNeeded() {
    boolean storeMetrics = Config.getBool("metrics.store.enabled", true);
    boolean storePlans = Config.getBool("plans.store.enabled", storeMetrics);
    if (!storeMetrics && !storePlans) {
      Config.setProperty("datasource.db.offline", "true");
      Config.setProperty("ebean.migration.run", "false");
      // Ebean requires an explicit platform name when the DataSource is offline
      Config.setProperty("ebean.databasePlatformName", "postgres");
    }
  }
}
