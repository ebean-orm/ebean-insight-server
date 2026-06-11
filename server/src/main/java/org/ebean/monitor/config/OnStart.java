package org.ebean.monitor.config;

import io.avaje.inject.PostConstruct;
//import io.avaje.metrics.annotation.Timed;
import io.avaje.metrics.annotation.Timed;
import io.ebean.DB;
import jakarta.inject.Singleton;
import org.ebean.monitor.Application;
import org.ebean.monitor.cleanup.CleanupPartitions;
import org.ebean.monitor.domain.DJob;
import org.ebean.monitor.ingest.PlanShapeBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

@Singleton
public class OnStart {

  private static final Logger log = LoggerFactory.getLogger(OnStart.class);

  private final CleanupPartitions cleanupPartitions = new CleanupPartitions();

  private final PlanShapeBackfill planShapeBackfill;

  OnStart(PlanShapeBackfill planShapeBackfill) {
    this.planShapeBackfill = planShapeBackfill;
  }

  @PostConstruct
  public void start() {
    if (Application.isForwardOnly()) {
      log.info("forward-only mode - skipping DB partition maintenance and data init");
      return;
    }
    periodicDbPartitionExtend();
    initData();
    backfillPlanShapes();
  }

  private void initData() {
    GlobalMetrics.init();
    DJob.find.initRollup();
  }

  /**
   * Backfill plan-shape fingerprints for plans captured before fingerprinting was
   * added (or with an older algorithm version). Runs once, off the startup thread.
   */
  private void backfillPlanShapes() {
    DB.backgroundExecutor().execute(planShapeBackfill::run);
  }

  /**
   * Register job to periodically extend the DB table partitions.
   */
  private void periodicDbPartitionExtend() {
    DB.backgroundExecutor().scheduleAtFixedRate(this::maintainPartitions, 1, 1, TimeUnit.DAYS);
  }

  @Timed
  private void maintainPartitions() {
    log.info("maintain db partitions");
    DB.script().run("/extend-partitions.sql");
    cleanupPartitions.run();
    cleanupPartitions.cleanupCaptureRequests();
  }
}
