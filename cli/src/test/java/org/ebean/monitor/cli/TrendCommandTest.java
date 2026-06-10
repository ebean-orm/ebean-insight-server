package org.ebean.monitor.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrendCommandTest {

  private static MetricTimeseries series() {
    Instant t = Instant.parse("2024-01-01T00:00:00Z");
    List<MetricTimeBucket> buckets = List.of(
        new MetricTimeBucket(t, 2L, 200L, 150L),
        new MetricTimeBucket(t.plusSeconds(60), 8L, 800L, 300L));
    return MetricTimeseries.builder()
        .app("myapp").hash("h").label("orm.Foo.bar")
        .windowMinutes(60L).bucketMinutes(1L)
        .buckets(buckets).build();
  }

  private static String capture(TrendCommand.Measure by) {
    PrintStream original = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
      TrendCommand.printTrend(series(), by);
    } finally {
      System.setOut(original);
    }
    return buf.toString(StandardCharsets.UTF_8);
  }

  @Test
  void total_topChartLabelledTotal_withSumHeadline_andCallsBelow() {
    String out = capture(TrendCommand.Measure.total);
    assertThat(out).contains("total (us)");
    assertThat(out).contains("total 1,000 us"); // 200 + 800
    assertThat(out).contains("calls");
    assertThat(out).contains("total 10");       // 2 + 8 calls
  }

  @Test
  void mean_topChartLabelledMean_withPeakHeadline() {
    String out = capture(TrendCommand.Measure.mean);
    assertThat(out).contains("mean (us)");
    assertThat(out).contains("peak 100 us");    // max(200/2=100, 800/8=100)
    assertThat(out).contains("calls");
  }

  @Test
  void max_topChartLabelledMax_withPeakHeadline() {
    String out = capture(TrendCommand.Measure.max);
    assertThat(out).contains("max (us)");
    assertThat(out).contains("peak 300 us");    // max(150, 300)
    assertThat(out).contains("calls");
  }

  @Test
  void count_rendersCallsOnly_noSecondChart() {
    String out = capture(TrendCommand.Measure.count);
    assertThat(out).contains("calls");
    assertThat(out).contains("total 10");
    assertThat(out).doesNotContain("(us)");
  }

  @Test
  void measureOf_mapsByName() {
    assertThat(TrendCommand.Measure.of("total")).isEqualTo(TrendCommand.Measure.total);
    assertThat(TrendCommand.Measure.of("mean")).isEqualTo(TrendCommand.Measure.mean);
    assertThat(TrendCommand.Measure.of("max")).isEqualTo(TrendCommand.Measure.max);
    assertThat(TrendCommand.Measure.of("count")).isEqualTo(TrendCommand.Measure.count);
  }
}
