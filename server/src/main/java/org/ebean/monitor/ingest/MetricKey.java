package org.ebean.monitor.ingest;

import io.ebeaninternal.server.util.Md5;
import org.ebean.monitor.api.MetricData;

class MetricKey {

  /**
   * Generate and return the unique key for a metric.
   */
  static String of(MetricData metric) {
    if (metric.hash != null && !metric.hash.isEmpty()) {
      // orm queries supply unique hash
      return metric.hash;
    } else if (metric.tags != null && !metric.tags.isEmpty()) {
      // v2 metrics without a hash (iud/txn/l2/web.api/jvm) share a family name
      // (e.g. "ebean.dml") so tags must be part of the identity to avoid collapse.
      return Md5.hash(metric.name + '|' + metric.tags);
    } else {
      return Md5.hash(metric.name);
    }
  }

}
