package org.ebean.monitor.config;

import io.ebean.annotation.Transactional;
import io.ebeaninternal.server.util.Md5;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class GlobalMetrics {

  private static final Logger log = LoggerFactory.getLogger(GlobalMetrics.class);

  private final static Map<String, DAppMetric> globalMetrics = new LinkedHashMap<>();

  private static final String[] GLOBAL_METRIC_NAMES = {
    "jvm.os.loadAverage",
    "jvm.threads.current", "jvm.threads.peak", "jvm.threads.daemon",
    "jvm.gc.time", "jvm.gc.count",
    "jvm.gc.time.g1-young-generation", "jvm.gc.count.g1-young-generation",

    "jvm.memory.nonheap.init", "jvm.memory.nonheap.used", "jvm.memory.nonheap.committed",
    "jvm.memory.heap.init", "jvm.memory.heap.used", "jvm.memory.heap.committed",
    "jvm.memory.heap.max", "jvm.memory.heap.pct",
    "jvm.memory.process.vmrss", "jvm.memory.process.vmhwm",

    "jvm.cgroup.memory.usageMb", "jvm.cgroup.memory.limit", "jvm.cgroup.memory.pctUsage",
    "jvm.cgroup.cpu.usageMicros", "jvm.cgroup.cpu.requests", "jvm.cgroup.cpu.limit",
    "jvm.cgroup.cpu.throttleMicros", "jvm.cgroup.cpu.numPeriod", "jvm.cgroup.cpu.numThrottle", "jvm.cgroup.cpu.pctThrottle",

    "app.log.error", "app.log.warn",
    "web.api", "web.api.error",
    "txn.main", "txn.readonly",
    "l2n.hitRatio", "l2n.hit", "l2n.miss", "l2n.put", "l2n.evict",
    "l2r.hitRatio", "l2r.hit", "l2r.miss", "l2r.put", "l2r.evict",
    "iud.insert", "iud.update", "iud.delete"
  };

  /**
   * Return the global metrics.
   */
  public static Map<String, DAppMetric> get() {
    return globalMetrics;
  }

  /**
   * Load all the global metrics.
   */
  public static void init() {
    final Map<String, DAppMetric> metrics = new GlobalMetrics().seedIfNeeded();
    log.info("loaded {} global metrics", metrics.size());
    globalMetrics.putAll(metrics);
  }

  private Map<String, DAppMetric> loadAll() {
    return new QDAppMetric()
      .app.isNull()
      .key.asMapKey()
      .id.asc()
      .findMap();
  }

  private Map<String, DAppMetric> seedIfNeeded() {
    if (!new QDAppMetric().app.isNull().exists()) {
      insertGlobalMetrics();
    }
    return loadAll();
  }

  @Transactional(batchSize = 500)
  private void insertGlobalMetrics() {
    int id = 0;
    for (String metricName : GLOBAL_METRIC_NAMES) {
      String key = Md5.hash(metricName);
      final DAppMetric metric = new DAppMetric(null, key, metricName);
      metric.setId(++id);
      metric.save();
    }
  }

}
