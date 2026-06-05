package org.ebean.monitor;

import io.avaje.config.Config;
import io.avaje.jex.AvajeJex;

public class Application {

  public static void main(String[] args) {
    AvajeJex.start();
  }

  /**
   * Returns true when both metrics and plans storage are disabled, i.e. the
   * server is running as a pure smart-proxy / forward-only with no Postgres.
   * <p>
   * Forward-only mode is wired via explicit builder calls in
   * {@code Configuration#database()} (offline pool, no migrations, fixed
   * platform name). This helper is consumed by {@code OnStart} and
   * {@code RollupService} to skip DB-touching startup work.
   */
  public static boolean isForwardOnly() {
    boolean storeMetrics = Config.getBool("metrics.store.enabled", true);
    boolean storePlans = Config.getBool("plans.store.enabled", storeMetrics);
    return !storeMetrics && !storePlans;
  }
}
