package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricData;

import java.util.List;

class IngestAppData extends IngestBase {

  public IngestAppData(IngestHeader header) {
    super(header, null);
  }

  @Override
  void createMetricEntries() {
    final List<MetricData> metrics = header.getMetrics();
    if (metrics != null && !metrics.isEmpty()) {
      header.collectAppAggregations();
      createMetricEntries(metrics);
    }
  }

}
