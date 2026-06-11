package org.ebean.monitor.cli;

import java.time.Instant;
import java.util.List;

import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractiveRenderTest {

  private static final String HASH = "a2e2082df04620910f8fa034561b3346";

  private static Interactive.Row top(String app, String hash, String label, long mean) {
    return new Interactive.Row(app, hash, label, mean, "us", 1000, 5_000_000, mean, 120_000, true, null, null);
  }

  @Test
  void renderList_topMode_showsCoreColumnsValuesAndShortHash() {
    var i = Interactive.forRender(Interactive.Mode.TOP, true);
    List<Interactive.Row> rows = List.of(
        top("central-access", HASH, "orm.A.find", 93203),
        top("central-access", "h2", "orm.B.find", 12612));

    String out = i.renderList("Top 2 by mean", rows);
    String header = headerLine(out.split("\n"));

    assertThat(header).contains("#").contains("APP").contains("LABEL")
        .contains("COUNT").contains("TOTAL(us)").contains("MEAN(us)").contains("MAX(us)")
        .contains("PLAN").contains("HASH").contains("chart");
    assertThat(out).contains("orm.A.find");
    assertThat(out).contains("93,203");        // grouped digits, no unit suffix in columns
    assertThat(out).contains("a2e2082df046");  // 12-char short hash
    assertThat(out).doesNotContain(HASH);      // full hash trimmed
    assertThat(out).contains("yes");           // plan-capable flag
  }

  @Test
  void renderList_singleApp_hidesAppColumn() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    String out = i.renderList("Top 1 by mean", List.of(top("central-access", HASH, "orm.A.find", 5)));
    assertThat(headerLine(out.split("\n"))).doesNotContain("APP");
  }

  @Test
  void renderList_missingMode_showsCapturesAndCaptured() {
    var i = Interactive.forRender(Interactive.Mode.MISSING, false);
    Interactive.Row r = new Interactive.Row("app", HASH, "orm.CDriver.driver_all_since",
        0, "us", 0, 0, 0, 0, false, 0L, null);

    String out = i.renderList("Missing plans 1 by total", List.of(r));
    String header = headerLine(out.split("\n"));

    assertThat(header).contains("CAPTURES").contains("CAPTURED").contains("HASH");
    assertThat(header).doesNotContain("PLAN");
    assertThat(out).contains("never");
  }

  @Test
  void parseCaptureIndices_spacesCommasAndDistinct() {
    assertThat(Interactive.parseCaptureIndices(" 1 7 8", 10)).containsExactly(1, 7, 8);
    assertThat(Interactive.parseCaptureIndices("1,7,8", 10)).containsExactly(1, 7, 8);
    assertThat(Interactive.parseCaptureIndices(" 3, 3  1 ", 10)).containsExactly(3, 1);
  }

  @Test
  void parseCaptureIndices_emptyAndInvalid() {
    assertThat(Interactive.parseCaptureIndices("", 10)).isEmpty();
    assertThat(Interactive.parseCaptureIndices("   ", 10)).isEmpty();
    assertThat(Interactive.parseCaptureIndices("1 99", 10)).isNull();   // out of range
    assertThat(Interactive.parseCaptureIndices("1 x", 10)).isNull();    // not a number
    assertThat(Interactive.parseCaptureIndices("0", 10)).isNull();      // below range
  }

  @Test
  void parseWindowMinutes_presets() {
    assertThat(Interactive.parseWindowMinutes("1h")).isEqualTo(60L);
    assertThat(Interactive.parseWindowMinutes("6h")).isEqualTo(360L);
    assertThat(Interactive.parseWindowMinutes("1d")).isEqualTo(1440L);
    assertThat(Interactive.parseWindowMinutes("7d")).isEqualTo(10080L);
    assertThat(Interactive.parseWindowMinutes("30d")).isEqualTo(43200L);
    assertThat(Interactive.parseWindowMinutes("t")).isEqualTo(-1L);     // a measure, not a window
    assertThat(Interactive.parseWindowMinutes("2h")).isEqualTo(-1L);    // unsupported preset
  }

  @Test
  void windowLabel_humanShortForm() {
    assertThat(Interactive.windowLabel(60L)).isEqualTo("1h");
    assertThat(Interactive.windowLabel(180L)).isEqualTo("3h");
    assertThat(Interactive.windowLabel(1440L)).isEqualTo("1d");
    assertThat(Interactive.windowLabel(10080L)).isEqualTo("7d");
    assertThat(Interactive.windowLabel(43200L)).isEqualTo("30d");
    assertThat(Interactive.windowLabel(45L)).isEqualTo("45m");
  }

  private static String headerLine(String[] lines) {
    for (String l : lines) {
      if (l.contains("LABEL")) {
        return l;
      }
    }
    throw new AssertionError("no header line containing LABEL");
  }

  // --- plan-shape rendering ---------------------------------------------------

  private static QueryPlanSummary planSummary(long id, Instant when, String shapeHash, Boolean changed) {
    return QueryPlanSummary.builder()
        .id(id).appMetricId(1L).envName("test").hash(HASH).label("orm.A.find")
        .queryTimeMicros(1234L).captureCount(2L).whenCaptured(when)
        .planShapeHash(shapeHash).shapeChanged(changed)
        .build();
  }

  private static MetricTimeseries timeseries(Instant start, int buckets) {
    List<MetricTimeBucket> list = new java.util.ArrayList<>();
    for (int i = 0; i < buckets; i++) {
      list.add(MetricTimeBucket.builder()
          .eventTime(start.plusSeconds(i * 60L)).count(1L).total(10L).max(5L).build());
    }
    return MetricTimeseries.builder().bucketMinutes(1L).buckets(list).build();
  }

  @Test
  void renderPlanTable_showsShapeAndChangeColumns() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    var base = Instant.parse("2026-06-01T00:00:00Z");
    var plans = List.of(
        planSummary(2, base.plusSeconds(120), "deadbeefcafebabe", true),
        planSummary(1, base.plusSeconds(60), "abcdef0123456789", false));

    String out = i.renderPlanTable(plans);
    assertThat(out).contains("SHAPE").contains("\u0394");
    assertThat(out).contains("deadbeef");   // 8-char short shape, newest first
    assertThat(out).contains("abcdef01");
    assertThat(out).contains("\u25C6");      // change-point glyph for id=2
  }

  @Test
  void renderPlanTable_nullShape_rendersEmDash() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    var base = Instant.parse("2026-06-01T00:00:00Z");
    var plans = List.of(planSummary(1, base, null, null));

    String out = i.renderPlanTable(plans);
    assertThat(out).contains("\u2014");      // em-dash placeholder
    assertThat(out).doesNotContain("\u25C6"); // no change glyph
  }

  @Test
  void renderPlanOverlay_headerCountsShapesAndChanges() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    var start = Instant.parse("2026-06-01T00:00:00Z");
    var ts = timeseries(start, 10);
    var plans = List.of(
        planSummary(3, start.plusSeconds(180), "B", true),
        planSummary(2, start.plusSeconds(120), "A", false),
        planSummary(1, start.plusSeconds(60), "A", false));

    String out = i.renderPlanOverlay(ts, plans);
    assertThat(out).contains("3 in window");
    assertThat(out).contains("2 shapes");
    assertThat(out).contains("1 change");
    assertThat(out).contains("\u25C6"); // change glyph in overlay
    assertThat(out).contains("\u25B2"); // normal capture glyph
  }

  @Test
  void renderPlanOverlay_singleShapeNoChanges_grammar() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    var start = Instant.parse("2026-06-01T00:00:00Z");
    var ts = timeseries(start, 10);
    var plans = List.of(planSummary(1, start.plusSeconds(60), "A", false));

    String out = i.renderPlanOverlay(ts, plans);
    assertThat(out).contains("1 shape,");
    assertThat(out).contains("0 changes");
  }

  @Test
  void renderPlanTable_oldServerNullFields_isNullSafe() {
    var i = Interactive.forRender(Interactive.Mode.TOP, false);
    var base = Instant.parse("2026-06-01T00:00:00Z");
    // old server: planShapeHash + shapeChanged null
    var plans = List.of(planSummary(1, base, null, null));
    String table = i.renderPlanTable(plans);
    assertThat(table).contains("\u2014");

    var start = Instant.parse("2026-06-01T00:00:00Z");
    String overlay = i.renderPlanOverlay(timeseries(start, 5),
        List.of(planSummary(1, start.plusSeconds(60), null, null)));
    assertThat(overlay).contains("0 shapes");
    assertThat(overlay).contains("0 changes");
    assertThat(overlay).doesNotContain("\u25C6");
  }

  @Test
  void distinctShapesAndChanges_helpers() {
    var base = Instant.parse("2026-06-01T00:00:00Z");
    var plans = List.of(
        planSummary(1, base, "A", false),
        planSummary(2, base, "B", true),
        planSummary(3, base, "A", true),
        planSummary(4, base, null, null));
    assertThat(Interactive.distinctShapes(plans)).isEqualTo(2);
    assertThat(Interactive.shapeChanges(plans)).isEqualTo(2);
  }

  @Test
  void shortShape_truncatesAndPlaceholders() {
    assertThat(Interactive.shortShape("deadbeefcafebabe")).isEqualTo("deadbeef");
    assertThat(Interactive.shortShape("abc")).isEqualTo("abc");
    assertThat(Interactive.shortShape(null)).isEqualTo("\u2014");
    assertThat(Interactive.shortShape("")).isEqualTo("\u2014");
  }
}
