package org.ebean.monitor.rollup;

import io.ebean.Database;
import io.ebean.annotation.Transactional;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DGaugeRollupM1;
import org.ebean.monitor.domain.DRollupJob;
import org.ebean.monitor.domain.DTimedRollupM1;
import org.ebean.monitor.domain.query.QDGaugeAgg;
import org.ebean.monitor.domain.query.QDTimedAgg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Rollup {

  private static final Logger log = LoggerFactory.getLogger(Rollup.class);

  private final RollupGroups rollupGroups = new RollupGroups();

  private final Instant eventTime;
  private final ZonedDateTime atZone;

  private long executionMillis;
  private long count;

  private final Map<String, DAppMetric> globalMetrics = new LinkedHashMap<>();

  public Rollup(Database database, Instant eventTime) {
    this.eventTime = eventTime;
    this.atZone = eventTime.atZone(ZoneOffset.UTC);
  }

  public void rollup() {
    performRollup();
    int rollupCount = rollupGroups.getCount();
    log.info("rollup time:{} millis:{} count:{} rollupGroups:{}", eventTime, executionMillis, count, rollupCount);
    extraRollups();
  }

  private void extraRollups() {
    try {
      if (mod10(atZone.getMinute())) {
        new RollupM10(eventTime).run();
        if (mod60(atZone.getMinute())) {
          new RollupM60(eventTime).run();
          if (daily(atZone.getHour())) {
            new RollupD1(eventTime).run();
          }
        }
      }
    } catch (Exception e) {
      log.error("Error performing rollup", e);
    }
  }

  private boolean daily(int hour) {
    return hour == 0;
  }

  boolean mod10(long minuteOfHour) {
    return minuteOfHour % 10 == 0;
  }

  boolean mod60(long minuteOfHour) {
    return minuteOfHour % 60 == 0;
  }

  @Transactional(batchSize = 500)
  private void performRollup() {
    final long start = System.currentTimeMillis();
    findAppMetrics();
    rollupEvents();
    rollupGroups.saveGauges();
    rollupTimed();
    rollupGroups.saveTimed();
    executionMillis = System.currentTimeMillis() - start;
    new DRollupJob(eventTime, executionMillis, count).save();
  }

  /**
   * Load partial for all app metrics (as we check their rollup group).
   */
  private void findAppMetrics() {
    final List<DAppMetric> appMetrics = DAppMetric.find.forRollup();
    for (DAppMetric appMetric : appMetrics) {
      final DApp app = appMetric.getApp();
      if (app == null) {
        final String name = appMetric.getName();
        if (name != null) {
          // register AppMetric for use as rollup group metric
          globalMetrics.put(name, appMetric);
        }
      }
    }
  }

  void rollupTimed() {

    QDTimedAgg a = QDTimedAgg.alias();

    new QDTimedAgg()
      .select(a.env, a.app, a.db, a.metric, a.eventTime, a.count, a.total, a.max)
      .eventTime.eq(eventTime)
      .findEach(timed -> {
        add(timed.asRollup());
        count++;
      });
  }

  void rollupEvents() {
    final QDGaugeAgg a = QDGaugeAgg.alias();

    new QDGaugeAgg()
      .select(a.env, a.app, a.metric, a.eventTime, a.count, a.total, a.max)
      .eventTime.eq(eventTime)
      .findEach(entry -> {
        add(entry.asRollup());
        count++;
      });
  }

  private void add(DTimedRollupM1 timed) {
    timed.save();
    final DAppMetric metric = timed.getMetric();
    final String group = metric.getRollupGroup();
    if (group != null) {
      // rollup group in memory aggregation
      final DAppMetric globalMetric = globalMetrics.get(group);
      if (globalMetric == null) {
        log.warn("skip unknown group metric [{}]", group);
      } else {
        rollupGroups.addTimed(globalMetric, timed);
      }
    }
  }

  private void add(DGaugeRollupM1 gauge) {
    gauge.save();
    final DAppMetric metric = gauge.getMetric();
    final String group = metric.getRollupGroup();
    if (group != null) {
      // rollup group in memory aggregation
      final DAppMetric globalMetric = globalMetrics.get(group);
      if (globalMetric == null) {
        log.warn("skip unknown group metric [{}]", group);
      } else {
        rollupGroups.addGauge(globalMetric, gauge);
      }
    }
  }
}
