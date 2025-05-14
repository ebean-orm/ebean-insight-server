package org.ebean.monitor.ingest;

import io.ebean.Transaction;
import io.ebean.annotation.Transactional;

import jakarta.inject.Singleton;

/**
 * Process the metric level ingestion - metrics and entries.
 */
@Singleton
class ProcessMetrics {

  /**
   * Ingest the metric level.
   * <p>
   * Creates metrics if needed and ingests all the entries.
   */
  @Transactional(batchSize = 500)
  void ingestMetrics(IngestHeader header) {
    Transaction.current().setSkipCache(false);
    // ingest application metrics
    new IngestAppData(header).createMetricEntries();

    // ingest database metrics
    for (IngestDbData dbDatum : header.getDbData()) {
      dbDatum.createMetricEntries();
    }
  }

}
