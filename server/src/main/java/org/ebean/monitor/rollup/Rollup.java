package org.ebean.monitor.rollup;

import io.ebean.Database;
import io.ebean.annotation.Transactional;
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

public class Rollup {

  private static final Logger log = LoggerFactory.getLogger(Rollup.class);

  private final Instant eventTime;
  private final ZonedDateTime atZone;

  private long executionMillis;
  private long count;

  public Rollup(Database database, Instant eventTime) {
    this.eventTime = eventTime;
    this.atZone = eventTime.atZone(ZoneOffset.UTC);
  }

  public void rollup() {
    performRollup();
    log.debug("rollup time:{} millis:{} count:{}", eventTime, executionMillis, count);
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
    rollupEvents();
    rollupTimed();
    executionMillis = System.currentTimeMillis() - start;
    new DRollupJob(eventTime, executionMillis, count).save();
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
  }

  private void add(DGaugeRollupM1 gauge) {
    gauge.save();
  }
}
