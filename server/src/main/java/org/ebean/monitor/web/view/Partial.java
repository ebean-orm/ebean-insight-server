package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;
import org.ebean.monitor.domain.*;

import java.util.List;

public class Partial {

  @JStache(path = "partial/apps")
  public record Apps(List<DApp> apps) {}

  @JStache(path = "partial/envs")
  public record Envs(List<DEnv> envs) {}

  @JStache(path = "partial/app-metrics")
  public record AppMetrics(List<DAppMetric> metrics) {}

  @JStache(path = "partial/metrics-recent")
  public record MetricsRecent(List<? extends BaseTimedEntry> metrics) {}

  @JStache(path = "partial/msg-pending")
  public record MsgPending(int count) {}

  @JStache(path = "partial/query-plans")
  public record QueryPlans(List<DQueryPlan> plans) {
  }
}
