package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.domain.DAppDatabase;

class IngestDbData extends IngestBase {

  private final MetricDbData metricDbData;

  IngestDbData(IngestHeader header, MetricDbData metricDbData, DAppDatabase mdb) {
    super(header, mdb);
    this.metricDbData = metricDbData;
  }

  @Override
  public void createMetricEntries() {
    createMetricEntries(metricDbData.metrics);
  }

}
