package org.ebean.monitor.forward;

import io.avaje.config.Config;
import jakarta.inject.Singleton;
import org.ebean.monitor.api.QueryPlanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits captured query plans to a dedicated SLF4J logger
 * ({@code org.ebean.monitor.queryplan}) so they can be indexed by log
 * aggregators (Loki, Sumo, CloudWatch). Useful in forward-only deployments
 * where the server has no Postgres.
 *
 * <p>Disabled by default; enable with {@code autoplan.logPlans=true}.
 * Bind values are excluded by default; opt in with
 * {@code autoplan.logPlans.includeBind=true}.
 */
@Singleton
public final class QueryPlanLogger {

  static final String LOGGER_NAME = "org.ebean.monitor.queryplan";
  private static final Logger planLog = LoggerFactory.getLogger(LOGGER_NAME);

  private final boolean enabled;
  private final boolean includeBind;

  public QueryPlanLogger() {
    this(Config.getBool("autoplan.logPlans", false),
      Config.getBool("autoplan.logPlans.includeBind", false));
  }

  QueryPlanLogger(boolean enabled, boolean includeBind) {
    this.enabled = enabled;
    this.includeBind = includeBind;
  }

  public boolean enabled() {
    return enabled;
  }

  public void log(QueryPlanRequest req) {
    if (!enabled || req == null || req.plans == null || req.plans.isEmpty()) {
      return;
    }
    if (!planLog.isInfoEnabled()) {
      return;
    }
    for (QueryPlanRequest.QPlan p : req.plans) {
      planLog.info(format(req, p));
    }
  }

  // package-private for tests
  String format(QueryPlanRequest req, QueryPlanRequest.QPlan p) {
    var sb = new StringBuilder(512);
    sb.append("QUERYPLAN app=").append(req.appName)
      .append(" env=").append(req.environment)
      .append(" hash=").append(p.hash)
      .append(" captureMicros=").append(p.captureMicros)
      .append(" captureCount=").append(p.captureCount)
      .append(" queryTimeMicros=").append(p.queryTimeMicros)
      .append(" label=\"").append(p.label == null ? "" : p.label).append('"')
      .append(" whenCaptured=").append(p.whenCaptured)
      .append('\n').append("sql: ").append(p.sql);
    if (includeBind && p.bind != null && !p.bind.isEmpty()) {
      sb.append('\n').append("bind: ").append(p.bind);
    }
    sb.append('\n').append("plan:\n").append(p.plan);
    return sb.toString();
  }
}
