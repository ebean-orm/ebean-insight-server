package org.ebean.monitor.ingest;

import io.avaje.config.Config;
import io.avaje.metrics.annotation.NotTimed;
import io.avaje.metrics.annotation.Timed;
import io.ebean.DB;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.forward.AutoPlanTrigger;
import org.ebean.monitor.forward.MetricForwarder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.avaje.inject.PostConstruct;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

/**
 * Reads the metrics data from the queue and ingests it into the DB.
 */
@Timed
@Singleton
public class IngestQueueConsumer {

  private static final Logger log = LoggerFactory.getLogger(IngestQueueConsumer.class);

  private static final long delayMillis = 200;

  private final IngestQueue queue;

  private final IngestMessage ingestMessage;
  private final MetricForwarder forwarder;
  private final AutoPlanTrigger autoPlanTrigger;
  private final boolean storeMetrics;

  IngestQueueConsumer(IngestQueue queue, IngestMessage ingestMessage, MetricForwarder forwarder, AutoPlanTrigger autoPlanTrigger) {
    this.queue = queue;
    this.ingestMessage = ingestMessage;
    this.forwarder = forwarder;
    this.autoPlanTrigger = autoPlanTrigger;
    this.storeMetrics = Config.getBool("metrics.store.enabled", true);
  }

  @NotTimed
  @PostConstruct
  public void start() {
    log.debug("starting ingest queue consumer");
    if (!storeMetrics) {
      log.info("metrics storage disabled (metrics.store.enabled=false) - running in forward-only mode");
    }
    DB.backgroundExecutor().scheduleWithFixedDelay(this::ingestFromQueue, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
  }

  private void ingestFromQueue() {
    log.trace("polling ...");
    MetricRequest data;
    while ((data = queue.poll()) != null) {
      ingestRequest(data);
    }

    QueryPlanRequest queryPlans;
    while ((queryPlans = queue.pollQueryPlans()) != null) {
      ingestQueryPlans(queryPlans);
    }
  }

  @Timed
  private void ingestRequest(MetricRequest data) {
    log.debug("ingesting request");
    // forward to OTLP (no-op if disabled, never throws)
    forwarder.forward(data);
    // detect expensive queries and request plan capture (no-op if disabled)
    try {
      autoPlanTrigger.onIngest(data);
    } catch (Exception e) {
      log.warn("autoplan trigger failed", e);
    }
    if (!storeMetrics) {
      return;
    }
    try {
      ingestMessage.ingest(data);
    } catch (Exception e) {
      log.error("Error ingesting request", e);
      // TODO: put onto a retry queue
    }
  }

  private void ingestQueryPlans(QueryPlanRequest queryPlans) {
    log.debug("ingesting query plans");
    try {
      ingestMessage.ingestQueryPlans(queryPlans);
    } catch (Exception e) {
      log.error("Error ingesting request", e);
      // TODO: put onto a retry queue
    }
  }

}
