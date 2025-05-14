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

  List<DQueryPlan> findQueryPlans(int appMetricId) {
    return new QDQueryPlan()
      .metric.id.eq(appMetricId)
      .orderBy()
      .whenCreated.desc()
      .findList();
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
