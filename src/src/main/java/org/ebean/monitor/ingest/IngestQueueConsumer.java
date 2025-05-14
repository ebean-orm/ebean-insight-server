package org.ebean.monitor.ingest;

//import io.avaje.metrics.annotation.NotTimed;
//import io.avaje.metrics.annotation.Timed;
import io.avaje.metrics.annotation.NotTimed;
import io.avaje.metrics.annotation.Timed;
import io.ebean.DB;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.QueryPlanRequest;
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

  IngestQueueConsumer(IngestQueue queue, IngestMessage ingestMessage) {
    this.queue = queue;
    this.ingestMessage = ingestMessage;
  }

  @NotTimed
  @PostConstruct
  public void start() {
    log.debug("starting ingest queue consumer");
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
