package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.domain.BaseEntry;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppDatabase;
import org.ebean.monitor.domain.DAppPod;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DGaugeEntry;
import org.ebean.monitor.domain.DTimedEntry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static java.math.BigDecimal.valueOf;

/**
 * Header level data for processing the metrics request.
 */
class IngestHeader {

  private final Instant eventTime;
  private final DEnv env;
  private final DApp app;
  private final DAppPod pod;
  private final List<IngestDbData> dbData = new ArrayList<>();
  private final List<MetricData> applicationMetrics;
  private boolean collectAppAggregations;

//  private final DTimedEntry webAgg = initAggregate();//"web.agg.Agg");
//  private final DTimedEntry webErr = initAggregate();//"web.agg.Err");

  IngestHeader(Instant eventTime, DEnv env, DApp app, DAppPod pod, List<MetricData> applicationMetrics) {
    this.eventTime = truncate(eventTime);
    this.env = env;
    this.app = app;
    this.pod = pod;
    this.applicationMetrics = applicationMetrics;
  }

  /**
   * Truncate the event time to the minute - expected ingestion per minute for DB metrics.
   */
  static Instant truncate(Instant eventTime) {
    return eventTime.truncatedTo(ChronoUnit.MINUTES);
  }

  /**
   * Add database metrics.
   */
  void add(MetricDbData db, DAppDatabase lookupDb) {
    dbData.add(new IngestDbData(this, db, lookupDb));
  }

  /**
   * Return database metrics to process.
   */
  List<IngestDbData> getDbData() {
    return dbData;
  }

  BaseEntry createMetricEntry(IngestEntry entry, DAppDatabase db) {
    final MetricData data = entry.getData();
    if (data.value != null) {
      return new DGaugeEntry(entry.getMetric(), env, app, eventTime, pod, valueOf(data.value));
    }

    DTimedEntry timed = new DTimedEntry(entry.getMetric(), env, app, eventTime, db, pod);
    timed.setCount(maybe(data.count));
    timed.setMax(maybe(data.max));
    timed.setMean(maybe(data.mean));
    timed.setTotal(maybe(data.total));
    return timed;
  }

  private Long maybe(Long val) {
    return (val == null) ? 0L : val;
  }

  DApp getApp() {
    return app;
  }

  public List<MetricData> getMetrics() {
    return applicationMetrics;
  }

  public void collectAppAggregations() {
    collectAppAggregations = true;
  }
}
