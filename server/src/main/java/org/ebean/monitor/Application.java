package org.ebean.monitor;

import io.avaje.config.Config;
import io.avaje.jex.AvajeJex;

public class Application {

  public static void main(String[] args) {
    // avaje-json's JParser caps string values at jsonb.parserMaxStringBuffer (default 50 000).
    // Postgres EXPLAIN plans can be much larger, so raise the limit before any jsonb class is
    // loaded (Recyclers reads this as a static final at class-load time).
    int planBufferSize = Config.getInt("ingest.planMaxStringBuffer", 2_000_000);
    System.setProperty("jsonb.parserMaxStringBuffer", String.valueOf(planBufferSize));
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
