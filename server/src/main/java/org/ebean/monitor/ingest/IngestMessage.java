package org.ebean.monitor.ingest;

import io.ebean.Database;
import io.avaje.config.Config;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import org.ebean.monitor.api.MetricRequest;

import jakarta.inject.Singleton;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.DQueryPlanChange;
import org.ebean.monitor.domain.DQueryPlanChange.ChangeType;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.ebean.monitor.domain.query.QDQueryPlanChange;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private final JsonType<Map<String, String>> tagsType;
  private final boolean changeEnabled;
  private final boolean firstObserved;

  IngestMessage(Database database, ProcessHeader lookup, ProcessMetrics lookupMetrics, Jsonb jsonb) {
    this.database = database;
    this.lookup = lookup;
    this.lookupMetrics = lookupMetrics;
    this.tagsType = jsonb.type(Types.mapOf(String.class));
    final boolean storePlans = Config.getBool("plans.store.enabled", Config.getBool("metrics.store.enabled", true));
    this.changeEnabled = Config.getBool("plans.change.enabled", storePlans);
    this.firstObserved = Config.getBool("plans.change.firstObserved", true);
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
        .setQueryTimeMicros(plan.queryTimeMicros)
        .setCaptureCount(plan.captureCount)
        .setCaptureMicros(plan.captureMicros)
        .setWhenCaptured(parseWhenCaptured(plan.whenCaptured));

      applyIdentity(newPlan, appMetric, plan.label);

      final var shape = PlanShape.fingerprint(plan.plan);
      if (shape != null) {
        newPlan.setPlanShape(shape.skeleton())
          .setPlanShapeHash(shape.hash())
          .setPlanShapeAlgo(PlanShape.ALGO);
      }

      newPlans.add(newPlan);
    }

    database.saveAll(newPlans);
    log.info("Obtained {} new query plans", newPlans.size());
    if (changeEnabled) {
      detectChanges(header.app(), header.env(), newPlans);
    }
    markCollected(header.app(), header.env(), queryPlans.plans);
  }

  /**
   * Detect plan-shape events for the freshly-saved plans and persist them.
   *
   * <p>For each new plan with a non-null shape (processed oldest-first) the most
   * recent prior plan in the same {@code (app, env, hash)} series with a non-null
   * shape and matching algorithm is found. No prior → FIRST (when enabled); a
   * differing prior shape → CHANGED; an identical prior shape → nothing. The
   * {@code toPlan} unique constraint makes this idempotent across retries.
   */
  private void detectChanges(DApp app, DEnv env, List<DQueryPlan> newPlans) {
    final List<DQueryPlan> ordered = new ArrayList<>(newPlans);
    ordered.sort(Comparator.comparing(DQueryPlan::whenCaptured,
      Comparator.nullsLast(Comparator.naturalOrder())));

    final List<DQueryPlanChange> events = new ArrayList<>();
    for (DQueryPlan plan : ordered) {
      final String shapeHash = plan.planShapeHash();
      final Integer algo = plan.planShapeAlgo();
      if (shapeHash == null || algo == null || plan.whenCaptured() == null) {
        continue;
      }
      if (changeEventExists(plan)) {
        continue;
      }
      final DQueryPlan prior = findPriorShaped(app, env, plan.hash(), algo, plan);
      final DQueryPlanChange event;
      if (prior == null) {
        if (!firstObserved) {
          continue;
        }
        event = new DQueryPlanChange(app, env, plan.hash(), plan)
          .setChangeType(ChangeType.FIRST);
      } else if (!shapeHash.equals(prior.planShapeHash())) {
        event = new DQueryPlanChange(app, env, plan.hash(), plan)
          .setChangeType(ChangeType.CHANGED)
          .setFromPlan(prior)
          .setFromShapeHash(prior.planShapeHash())
          .setFromQueryTimeMicros(prior.queryTimeMicros());
      } else {
        continue;
      }
      event
        .setName(plan.name())
        .setKind(plan.kind())
        .setType(plan.type())
        .setLabel(plan.label())
        .setToShapeHash(shapeHash)
        .setAlgo(algo)
        .setToQueryTimeMicros(plan.queryTimeMicros())
        .setWhenCaptured(plan.whenCaptured())
        .setDetectedAt(Instant.now());
      events.add(event);
    }
    if (!events.isEmpty()) {
      database.saveAll(events);
      log.info("Detected {} query plan shape events", events.size());
    }
  }

  private boolean changeEventExists(DQueryPlan toPlan) {
    return new QDQueryPlanChange()
      .toPlan.eq(toPlan)
      .exists();
  }

  /**
   * The most recent prior plan (by whenCaptured, then id) for this series with a
   * non-null shape and matching algorithm, excluding the plan itself.
   */
  @Nullable
  private DQueryPlan findPriorShaped(DApp app, DEnv env, String hash, int algo, DQueryPlan current) {
    return new QDQueryPlan()
      .app.eq(app)
      .env.eq(env)
      .hash.eq(hash)
      .planShapeAlgo.eq(algo)
      .planShapeHash.isNotNull()
      .whenCaptured.lt(current.whenCaptured())
      .id.ne(current.getId())
      .orderBy().whenCaptured.desc().id.desc()
      .setMaxRows(1)
      .findOne();
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

  /**
   * Resolve the v2 identity (name/kind/type/label) for a captured plan from its
   * matched metric, so the plan row is self-describing and display/filtering need
   * no read-time metric join.
   *
   * <p>When the metric is present its canonical family {@code name} plus
   * {@code kind}/{@code type}/{@code label} tags are copied. When no metric is
   * found (capture arrived before the metric, or a v1 client) we fall back to the
   * client-sent flat label (e.g. {@code orm.Customer.findList}): split off a known
   * {@code orm.}/{@code dto.}/{@code sql.} prefix as {@code kind}, the remainder as
   * {@code label}, {@code type} null, {@code name} the {@code ebean.query} family.
   */
  private void applyIdentity(DQueryPlan plan, @Nullable DAppMetric metric, @Nullable String clientLabel) {
    if (metric != null) {
      final Map<String, String> tags = parseTags(metric.getTags());
      final String label = tags.get("label");
      plan.setName(metric.getName())
        .setKind(tags.get("kind"))
        .setType(tags.get("type"))
        .setLabel(label != null ? label : metric.getName());
      return;
    }
    // fallback: derive from the client-sent flat label
    plan.setName("ebean.query");
    if (clientLabel == null) {
      return;
    }
    final int dot = clientLabel.indexOf('.');
    if (dot > 0) {
      final String prefix = clientLabel.substring(0, dot);
      if (prefix.equals("orm") || prefix.equals("dto") || prefix.equals("sql")) {
        plan.setKind(prefix).setLabel(clientLabel.substring(dot + 1));
        return;
      }
    }
    plan.setLabel(clientLabel);
  }

  private Map<String, String> parseTags(@Nullable String tagsJson) {
    if (tagsJson == null || tagsJson.isBlank()) {
      return Map.of();
    }
    final Map<String, String> tags = tagsType.fromJson(tagsJson);
    return tags != null ? tags : Map.of();
  }
}
