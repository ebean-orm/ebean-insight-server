package org.ebean.monitor.ingest;

import io.ebean.Database;
import org.ebean.monitor.api.MetricRequest;

import jakarta.inject.Singleton;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ingests the metrics request into the DB.
 */
@Singleton
public class IngestMessage {

  private static final Logger log = LoggerFactory.getLogger(IngestMessage.class);

  private final Database database;
  private final ProcessHeader lookup;
  private final ProcessMetrics lookupMetrics;

  IngestMessage(Database database, ProcessHeader lookup, ProcessMetrics lookupMetrics) {
    this.database = database;
    this.lookup = lookup;
    this.lookupMetrics = lookupMetrics;
  }

  /**
   * Ingest a request persisting it to the DB.
   */
  public void ingest(MetricRequest request) {
    // first ingest the header
    final IngestHeader header = lookup.ingestHeader(request);
    if (header != null) {
      // now ingest all the metrics
      lookupMetrics.ingestMetrics(header);
    }
  }

  public void ingestQueryPlans(QueryPlanRequest queryPlans) {
    final var header = lookup.ingestHeader(queryPlans);
    List<DQueryPlan> newPlans = new ArrayList<>();
    for (QueryPlanRequest.QPlan plan : queryPlans.plans) {
      DAppMetric appMetric = findAppMetric(header.app(), plan.hash);
      if (appMetric == null) {
        log.warn("Unable to find AppMetric for query plan hash {} app {}", plan.hash, header.app());
      }
      var newPlan = new DQueryPlan(header.app(), header.env(), appMetric)
        .setBind(plan.bind)
        .setPlan(plan.plan)
        .setSql(plan.sql)
        .setHash(plan.hash)
        .setCaptureCount(plan.captureCount)
        .setCaptureMicros(plan.captureMicros)
        .setLabel(plan.label)
        .setWhenCaptured(parseWhenCaptured(plan.whenCaptured));

      newPlans.add(newPlan);
    }

    database.saveAll(newPlans);
    log.info("Obtained {} new query plans", newPlans.size());
    markCollected(header.app(), header.env(), queryPlans.plans);
  }

  /**
   * Mark any open (durable) capture requests for these hashes as collected, so
   * the in-flight pending view stops reporting them once the plan has landed.
   *
   * <p>Matches both env-specific requests and "any environment" requests (env
   * null); the latter have their env filled in from the plan that arrived.
   */
  private void markCollected(DApp app, DEnv env, List<QueryPlanRequest.QPlan> plans) {
    final Set<String> hashes = new LinkedHashSet<>();
    for (QueryPlanRequest.QPlan plan : plans) {
      if (plan.hash != null) {
        hashes.add(plan.hash);
      }
    }
    if (hashes.isEmpty()) {
      return;
    }
    final List<DCaptureRequest> open = new QDCaptureRequest()
      .app.eq(app)
      .hash.in(hashes)
      .collectedAt.isNull()
      .or()
        .env.eq(env)
        .env.isNull()
      .endOr()
      .findList();
    if (open.isEmpty()) {
      return;
    }
    final Instant now = Instant.now();
    for (DCaptureRequest request : open) {
      if (request.env() == null) {
        request.setEnv(env);
      }
      request.setCollectedAt(now);
    }
    database.saveAll(open);
  }

  private static Instant parseWhenCaptured(String whenCaptured) {
    try {
      return Instant.parse(whenCaptured);
    } catch (DateTimeParseException e) {
      return Instant.now();
    }
  }

  @Nullable
  private DAppMetric findAppMetric(DApp app, String hash) {
    return new QDAppMetric()
      .app.eq(app)
      .key.eq(hash)
      .findOne();
  }
}
