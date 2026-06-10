package org.ebean.monitor.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractiveRenderTest {

  @Test
  void columnTitle_timeMeasures_haveUsSuffix() {
    assertThat(Interactive.columnTitle("total", "us")).isEqualTo("TOTAL(us)");
    assertThat(Interactive.columnTitle("mean", "us")).isEqualTo("MEAN(us)");
    assertThat(Interactive.columnTitle("max", "us")).isEqualTo("MAX(us)");
  }

  @Test
  void columnTitle_count_hasNoUsSuffix() {
    assertThat(Interactive.columnTitle("count", "calls")).isEqualTo("COUNT");
  }

  @Test
  void renderList_hasHeaderRowWithIndexLabelAndValueTitle() {
    var i = Interactive.forRender(false, "MEAN(us)");
    List<Interactive.Row> rows = List.of(
        new Interactive.Row("app", "h1", "orm.A.find", 93203, "us"),
        new Interactive.Row("app", "h2", "orm.B.find", 12612, "us"));

    String out = i.renderList("Top 2 by mean", rows);
    String[] lines = out.split("\n");

    // title line, blank, header line, then rows
    assertThat(out).contains("Top 2 by mean");
    String header = headerLine(lines);
    assertThat(header).contains("#");
    assertThat(header).contains("LABEL");
    assertThat(header).contains("MEAN(us)");
    assertThat(out).contains("orm.A.find");
    assertThat(out).contains("93,203 us");
  }

  @Test
  void renderList_zeroValues_stillTitlesValueColumn() {
    var i = Interactive.forRender(true, "TOTAL(us)");
    List<Interactive.Row> rows = List.of(
        new Interactive.Row("app", "h1", "orm.CDriver.driver_all_since", 0, "us"),
        new Interactive.Row("app", "h2", "orm.CDriver.driver_by_gid", 0, "us"));

    String out = i.renderList("Missing plans 2 by total", rows);

    assertThat(headerLine(out.split("\n"))).contains("TOTAL(us)");
    assertThat(out).contains("0 us");
    // value title aligns with the value column (both end-justified to width 14)
    assertThat(out).contains("     TOTAL(us)");
  }

  @Test
  void renderChart_additive_showsCumColumnHeader() {
    var i = Interactive.forRender(true, "TOTAL(us)");
    List<Interactive.Row> rows = List.of(
        new Interactive.Row("app", "h1", "orm.A.find", 100, "us"),
        new Interactive.Row("app", "h2", "orm.B.find", 50, "us"));

    String out = i.renderChart("Top 2 by total", rows);

    String header = headerLine(out.split("\n"));
    assertThat(header).contains("#");
    assertThat(header).contains("LABEL");
    assertThat(header).contains("TOTAL(us)").contains("CUM%");
    assertThat(out).contains("(cum");
  }

  @Test
  void renderChart_nonAdditive_omitsCumColumnHeader() {
    var i = Interactive.forRender(false, "MEAN(us)");
    List<Interactive.Row> rows = List.of(
        new Interactive.Row("app", "h1", "orm.A.find", 100, "us"));

    String out = i.renderChart("Top 1 by mean", rows);

    String header = headerLine(out.split("\n"));
    assertThat(header).contains("MEAN(us)");
    assertThat(header).doesNotContain("CUM%");
    assertThat(out).doesNotContain("(cum");
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
