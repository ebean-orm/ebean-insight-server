package org.ebean.monitor.rollup;

import io.ebean.annotation.Transactional;
import org.ebean.monitor.domain.DGaugeRollup;
import org.ebean.monitor.domain.DTimedAgg;
import org.ebean.monitor.domain.query.QDGaugeRollup;
import org.ebean.monitor.domain.query.QDTimedAgg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public abstract class RollupMBase {

  static final Logger log = LoggerFactory.getLogger(RollupMBase.class);

  private final long start = System.currentTimeMillis();
  final Instant endTime;
  final Instant startTime;
  long timedCount;
  long gaugeCount;

  public RollupMBase(Instant endTime, int mins) {
    this.endTime = endTime;
    this.startTime = endTime.minus(mins, ChronoUnit.MINUTES);
  }

  public RollupMBase(Instant endTime, Instant startTime) {
    this.endTime = endTime;
    this.startTime = startTime;
  }

  public void run() {
    performRollup();
    long executionMillis = System.currentTimeMillis() - start;
    log.info("rollup{} time:{} millis:{} timed:{} gauge:{}", label(), endTime, executionMillis, timedCount, gaugeCount);
  }

  @Transactional(batchSize = 500)
  private void performRollup() {
    rollupTimed();
    rollupGauges();
  }

  abstract String label();

  abstract String baseTableTimed();

  abstract String baseTableGauge();

  abstract void saveTimed(DTimedAgg timed);

  abstract void saveGauge(DGaugeRollup gauge);

  private void rollupTimed() {
    QDTimedAgg a = QDTimedAgg.alias();
    new QDTimedAgg()
      .setBaseTable(baseTableTimed())
      .select(a.app, a.metric, a.env, a.db, a.count, a.total, a.max)
      .eventTime.gt(startTime)
      .eventTime.le(endTime)
      .findEach(this::saveTimed);
  }

  private void rollupGauges() {
    QDGaugeRollup a = QDGaugeRollup.alias();
    new QDGaugeRollup()
      .setBaseTable(baseTableGauge())
      .select(a.app, a.metric, a.env, a.count, a.total, a.max)
      .eventTime.gt(startTime)
      .eventTime.le(endTime)
      .findEach(this::saveGauge);
  }

}
