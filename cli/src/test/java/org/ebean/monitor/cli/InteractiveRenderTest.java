package org.ebean.monitor.cli;

import java.util.List;

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

  private static String headerLine(String[] lines) {
    for (String l : lines) {
      if (l.contains("LABEL")) {
        return l;
      }
    }
    throw new AssertionError("no header line containing LABEL");
  }
}
