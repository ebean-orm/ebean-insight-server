package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jex.http.NotFoundException;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.QueryHashView;
import org.ebean.monitor.web.view.QueryHashView.PlanRow;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Complete metadata, SQL, statistics, and plan history for one query hash. */
@Html
@Controller
@Path("/ux")
public class UIQueryHashController {

  private static final DateTimeFormatter INSTANT_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter PLAN_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);
  private static final int PLANS_LIMIT = 50;

  private final V1QueryService service;

  public UIQueryHashController(V1QueryService service) {
    this.service = service;
  }

  @Get("query-hash")
  QueryHashView queryHash(@QueryParam("app") String app,
                          @QueryParam("env") @Nullable String envParam,
                          @QueryParam("range") @Nullable String rangeParam,
                          @QueryParam("label") @Nullable String labelParam,
                          @QueryParam("hash") String hash,
                          @QueryParam("from") @Nullable String fromParam,
                          @QueryParam("to") @Nullable String toParam) {
    final Instant from = parseInstant(fromParam, "from");
    final Instant to = parseInstant(toParam, "to");
    if ((from == null) != (to == null)) {
      throw new io.avaje.jex.http.BadRequestException("Both from and to timestamps are required");
    }
    final String env = envParam == null || envParam.isBlank() ? null : envParam;
    final String range = UIQueryTotalController.rangeKey(rangeParam, from, to);
    final String topUrl = UIQueryTotalController.topUrl(
      app, env == null ? "" : env, range, from, to);
    final AppMetric metric = service.getMetricByHash(app, hash).stream().findFirst()
      .orElseThrow(() -> new NotFoundException("No metric for hash " + hash));
    final String label = labelParam == null || labelParam.isBlank() ? metric.label() : labelParam;
    final String detailUrl = UIQueryTotalController.metricDetailUrl(app, envParam, range, label, from, to);
    final List<AppMetricStats> stats = from == null
      ? service.getMetricStatsByHash(app, hash, rangeMinutes(range), null, env)
      : service.getMetricStatsByHash(app, hash, env, from, to);
    final AppMetricStats stat = stats.isEmpty() ? null : stats.getFirst();
    final List<PlanRow> plans = service.listPlans(app, env, label, hash,
        null, null, null, null, PLANS_LIMIT).stream()
      .map(plan -> planRow(plan, range, from, to))
      .toList();

    return new QueryHashView(
      new Breadcrumb(List.of(
        new Breadcrumb.Item(topUrl, "Top"),
        new Breadcrumb.Item(detailUrl, label),
        new Breadcrumb.Item(metric.key()))),
      app, env == null ? "" : env, label, metric.key(),
      value(metric.loc()), formatInstant(metric.createdAt()), formatInstant(metric.modifiedAt()),
      value(metric.sql()), formatMs(stat == null ? 0L : stat.totalMicros()),
      formatMs(stat == null ? 0L : stat.meanMicros()), formatMs(stat == null ? 0L : stat.maxMicros()),
      formatNum(stat == null ? 0L : stat.count()), plans);
  }

  private PlanRow planRow(QueryPlanSummary plan, String range, @Nullable Instant from, @Nullable Instant to) {
    return new PlanRow(plan.id(), plan.envName(), PLAN_FORMAT.format(plan.whenCaptured()),
      formatMs(plan.queryTimeMicros()), formatNum(plan.captureCount()),
      Boolean.TRUE.equals(plan.shapeChanged()),
      UIQueryTotalController.queryPlanUrl(plan.id(), range, from, to));
  }

  private static long rangeMinutes(String range) {
    return RangeOptions.resolve(range).minutes();
  }

  private static String formatInstant(@Nullable Instant value) {
    return value == null ? "—" : INSTANT_FORMAT.format(value);
  }

  private static String formatMs(long micros) {
    return formatNum(micros / 1000L);
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  private static String value(@Nullable String value) {
    return value == null ? "" : value;
  }

  @Nullable
  private static Instant parseInstant(@Nullable String value, String parameter) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new io.avaje.jex.http.BadRequestException("Invalid " + parameter + " timestamp");
    }
  }
}
