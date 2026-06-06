package org.ebean.monitor.web;

import io.avaje.inject.Component;
import org.ebean.monitor.domain.*;
import org.ebean.monitor.domain.query.*;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Component
final class UIService {

  List<DApp> findApps() {
    return new QDApp()
      .orderBy().name.asc()
      .findList();
  }

  DApp findApp(int id) {
    return new QDApp()
      .id.eq(id)
      .findOneOrEmpty()
      .orElseThrow();
  }

  List<DAppMetric> findMetrics(int appId) {
    return new QDAppMetric()
      .app.id.eq(appId)
      .orderBy()
      .name.asc()
      .findList();
  }

  DAppMetric findAppMetric(int appMetricId) {
    return new QDAppMetric()
      .id.eq(appMetricId)
      .findOneOrEmpty()
      .orElseThrow();
  }

  List<? extends BaseTimedEntry> findMetricsRecent(int appMetricId) {
    Instant now = Instant.now();
    Instant start = now.minus(10, ChronoUnit.MINUTES);

    return new QDTimedEntry()
      .metric.id.eq(appMetricId)
      //.eventTime.inRange(start, now)
      .orderBy()
      .eventTime.desc()
      .findList();
  }

  DEnv findEnv(String environment) {
    String env = Objects.requireNonNull(environment).trim();
    return new QDEnv()
      .name.eq(env)
      .findOneOrEmpty()
      .orElseThrow();
  }

  List<DEnv> findAllEnvs() {
    return new QDEnv()
      .findList();
  }

  List<DQueryPlan> findQueryPlans(int appMetricId, int max) {
    return new QDQueryPlan()
      .metric.id.eq(appMetricId)
      .orderBy()
      .whenCreated.desc()
      .setMaxRows(max)
      .findList();
  }

  List<DQueryPlan> findRecentQueryPlans(int max, @Nullable String app, @Nullable String env,
                                        @Nullable String label, @Nullable String hash,
                                        @Nullable Integer sinceMinutes) {
    QDQueryPlan query = new QDQueryPlan();
    query.app.name.eqIfPresent(trimToNull(app));
    query.env.name.eqIfPresent(trimToNull(env));
    query.label.eqIfPresent(trimToNull(label));
    query.hash.eqIfPresent(trimToNull(hash));
    if (sinceMinutes != null && sinceMinutes > 0) {
      query.whenCreated.gt(Instant.now().minus(sinceMinutes, ChronoUnit.MINUTES));
    }
    return query
      .orderBy()
      .whenCreated.desc()
      .setMaxRows(max)
      .findList();
  }

  @Nullable
  private static String trimToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    value = value.trim();
    return value.isEmpty() ? null : value;
  }

  @Nullable
  DQueryPlan findQueryPlan(int planId) {
    return new QDQueryPlan()
      .id.eq(planId)
      .findOne();
  }

  List<TimeEvents> dashOne() {
    Instant lastHour = Instant.now();//.truncatedTo(ChronoUnit.HOURS);
    Instant start = lastHour.minus(1, ChronoUnit.HOURS);

    var r = QDTimedEntry.alias();
    return new QDTimedEntry()
      .select(r.eventTime, r.total)
      .eventTime.between(start, lastHour)
      .metric.name.eq("txn.named.ProcessMetrics.ingestMetrics")
      .findList()
      .stream()
      .map(a -> new TimeEvents(a.getEventTime(), a.getTotal()))
      .toList();
  }

  List<TimeEvents> dashOne2() {
    Instant lastHour = Instant.now();//.truncatedTo(ChronoUnit.HOURS);
    Instant start = lastHour.minus(1, ChronoUnit.HOURS);

    var r = QDTimedEntry.alias();
    return new QDTimedEntry()
      .select(r.eventTime, r.total)
      .eventTime.between(start, lastHour)
      .metric.name.eq("txn.named.RollupMBase.performRollup")
      .findList()
      .stream()
      .map(a -> new TimeEvents(a.getEventTime(), a.getTotal()))
      .toList();
  }
}
