package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.domain.DAppMetric;

class IngestEntry {

  private final String key;
  private final MetricData data;
  private DAppMetric metric;

  IngestEntry(String key, MetricData data) {
    this.key = key;
    this.data = data;
  }

  IngestEntry assignMetric(DAppMetric metric) {
    this.metric = metric;
    return this;
  }

  String getKey() {
    return key;
  }

  MetricData getData() {
    return data;
  }

  DAppMetric getMetric() {
    return metric;
  }
}

