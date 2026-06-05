package org.ebean.monitor;

import io.avaje.config.Config;
import io.avaje.jex.AvajeJex;

public class Application {

  public static void main(String[] args) {
    // Eagerly touch avaje-config from main() before AvajeJex.start() so that
    // env-var bindings are bound at this point. Without this, in GraalVM
    // native image, the first Config call from inside an avaje-inject bean
    // factory (Configuration#database) returned yaml defaults instead of
    // env-var overrides — causing forward-only mode to silently fail.
    // (RC4 worked because configureForwardOnlyIfNeeded() did a Config call
    // here; RC9 regressed when that method was removed.)
    isForwardOnly();
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
