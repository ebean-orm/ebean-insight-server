package org.ebean.monitor.config;

import io.avaje.inject.PostConstruct;
//import io.avaje.metrics.annotation.Timed;
import io.avaje.metrics.annotation.Timed;
import io.ebean.DB;
import jakarta.inject.Singleton;
import org.ebean.monitor.Application;
import org.ebean.monitor.cleanup.CleanupPartitions;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.DJob;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.ebean.monitor.ingest.PlanShapeBackfill;
import org.ebean.monitor.web.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Singleton
public class OnStart {

  private static final Logger log = LoggerFactory.getLogger(OnStart.class);
  private static final long PENDING_STALE_MINUTES = 15L;

  private final CleanupPartitions cleanupPartitions = new CleanupPartitions();

  private final PlanShapeBackfill planShapeBackfill;
  private final MessageService messageService;

  OnStart(PlanShapeBackfill planShapeBackfill, MessageService messageService) {
    this.planShapeBackfill = planShapeBackfill;
    this.messageService = messageService;
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
    rehydratePendingCaptures();
  }

  /**
   * Re-push any uncollected capture requests from the DB into the in-memory queue.
   */
  private void rehydratePendingCaptures() {
    final Instant from = Instant.now().minus(Duration.ofMinutes(PENDING_STALE_MINUTES));
    final var pending = new QDCaptureRequest()
      .collectedAt.isNull()
      .requestedAt.gt(from)
      .findList();
    int count = pushPendingCaptures(pending);
    if (count > 0) {
      log.info("rehydrated {} pending capture request(s) from DB into message queue", count);
    }
  }

  int pushPendingCaptures(Iterable<DCaptureRequest> pending) {
    int count = 0;
    for (var r : pending) {
      final String env = r.env() != null ? r.env().getName() : MessageService.ANY_ENV;
      messageService.pushMessage(r.app().getName(), env, "qp:" + r.hash());
      count++;
    }
    return count;
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
