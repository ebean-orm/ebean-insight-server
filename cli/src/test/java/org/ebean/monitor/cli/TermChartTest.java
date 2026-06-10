package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TermChartTest {

  @Test
  void bar_zeroValue_isAllSpaces() {
    assertThat(TermChart.bar(0, 100, 10)).isEqualTo("          ");
  }

  @Test
  void bar_fullValue_isAllFullBlocks() {
    assertThat(TermChart.bar(100, 100, 8)).isEqualTo("\u2588".repeat(8));
  }

  @Test
  void bar_half_isPaddedToWidth() {
    String b = TermChart.bar(50, 100, 10);
    assertThat(b).hasSize(10);
    assertThat(b).startsWith("\u2588\u2588\u2588\u2588\u2588");
  }

  @Test
  void bar_zeroMax_isSafe() {
    assertThat(TermChart.bar(5, 0, 6)).isEqualTo("      ");
  }

  @Test
  void bar_zeroWidth_isEmpty() {
    assertThat(TermChart.bar(5, 10, 0)).isEmpty();
  }

  @Test
  void sparkline_lengthMatchesInput() {
    long[] v = {1, 5, 3, 8, 2, 9, 4};
    assertThat(TermChart.sparkline(v)).hasSize(v.length);
  }

  @Test
  void sparkline_minIsLowestBlock_maxIsHighest() {
    long[] v = {0, 100};
    String s = TermChart.sparkline(v);
    assertThat(s.charAt(0)).isEqualTo('\u2581');
    assertThat(s.charAt(1)).isEqualTo('\u2588');
  }

  @Test
  void sparkline_flat_isAllLowest() {
    long[] v = {7, 7, 7};
    assertThat(TermChart.sparkline(v)).isEqualTo("\u2581\u2581\u2581");
  }

  @Test
  void sparkline_emptyOrNull_isEmpty() {
    assertThat(TermChart.sparkline(new long[0])).isEmpty();
    assertThat(TermChart.sparkline(null)).isEmpty();
  }

  @Test
  void columns_heightAndWidthMatchInputs() {
    long[] v = {0, 1, 2, 3, 4};
    String[] rows = TermChart.columns(v, 4);
    assertThat(rows).hasSize(4);
    for (String r : rows) {
      assertThat(r).hasSize(5);
    }
  }

  @Test
  void columns_maxValueFillsFullHeight() {
    long[] v = {0, 10};
    String[] rows = TermChart.columns(v, 3);
    for (String r : rows) {
      assertThat(r.charAt(1)).isEqualTo('\u2588');
    }
    assertThat(rows[0].charAt(0)).isEqualTo(' ');
  }

  @Test
  void columns_nonZeroValueShowsAtLeastBottomCell() {
    long[] v = {100, 1};
    String[] rows = TermChart.columns(v, 5);
    assertThat(rows[rows.length - 1].charAt(1)).isNotEqualTo(' ');
    assertThat(rows[0].charAt(1)).isEqualTo(' ');
  }

  @Test
  void columns_zeroHeightIsEmpty() {
    assertThat(TermChart.columns(new long[]{1, 2}, 0)).isEmpty();
  }

  @Test
  void columns_zeroColumnKeepsBaselineOnBottomRow() {
    long[] v = {0, 10, 0};
    String[] rows = TermChart.columns(v, 4);
    String bottom = rows[rows.length - 1];
    // every column has a baseline on the x-axis (no gaps), even the zero ones
    assertThat(bottom.charAt(0)).isEqualTo('\u2581');
    assertThat(bottom.charAt(2)).isEqualTo('\u2581');
    // ...but rows above a zero column stay empty
    assertThat(rows[0].charAt(0)).isEqualTo(' ');
  }

  @Test
  void columns_allZeroStillDrawsBaseline() {
    String[] rows = TermChart.columns(new long[]{0, 0, 0}, 3);
    assertThat(rows[rows.length - 1]).isEqualTo("\u2581\u2581\u2581");
    assertThat(rows[0]).isEqualTo("   ");
  }

  @Test
  void fit_underTarget_isUnchanged() {
    long[] v = {1, 2, 3};
    assertThat(TermChart.fit(v, 60, false)).isSameAs(v);
    assertThat(TermChart.fit(v, 60, true)).isSameAs(v);
  }

  @Test
  void fit_sumAggregatesGroups() {
    long[] v = {1, 1, 1, 1, 1, 1};
    long[] out = TermChart.fit(v, 3, true);
    assertThat(out).containsExactly(2, 2, 2);
  }

  @Test
  void fit_averageAggregatesGroups() {
    long[] v = {10, 20, 30, 40};
    long[] out = TermChart.fit(v, 2, false);
    assertThat(out).containsExactly(15, 35);
  }

  @Test
  void fit_maxAggregatesGroups() {
    long[] v = {10, 90, 30, 40, 5, 70};
    long[] out = TermChart.fit(v, 3, TermChart.Agg.MAX);
    assertThat(out).containsExactly(90, 40, 70);
  }

  @Test
  void fit_booleanOverloadDelegatesToSumOrAvg() {
    long[] v = {10, 20, 30, 40};
    assertThat(TermChart.fit(v, 2, true)).containsExactly(30, 70);
    assertThat(TermChart.fit(v, 2, false)).containsExactly(15, 35);
  }
}
