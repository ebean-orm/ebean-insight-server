package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import io.avaje.http.client.HttpException;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Show the per-bucket time-series (trend) for a single metric.
 *
 * <p>Plots how a metric moves over a window using stacked column charts. The
 * tall top chart plots the {@code --by} measure (total, mean, max — derived from
 * the raw additive components count/total/max the server returns per bucket) and
 * the short lower chart always plots call volume. {@code --by count} plots calls
 * as the headline chart on its own.
 */
@Command(name = "trend", mixinStandardHelpOptions = true,
    description = "Show the per-bucket trend for one metric (top chart --by total|mean|max|count; lower chart is calls).",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight trend myapp <hash>                        # top chart: mean (default)",
        "  insight trend myapp <hash> --by total             # top chart: total time",
        "  insight trend myapp <hash> --by max --since-hours 6 --env test",
        "  insight trend --app myapp --hash <hash> -o json",
        "  # find the hash first:  insight top --app myapp   (HASH column)"
    })
final class TrendCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", arity = "0..1", description = "Application name (falls back to --app or config).")
  @Nullable String appArg;

  @Parameters(index = "1", arity = "0..1", description = "Metric hash (falls back to --hash).")
  @Nullable String hashArg;

  @Option(names = "--app", description = "Application name (alternative to the positional).")
  @Nullable String appOpt;

  @Option(names = "--hash", description = "Metric hash (alternative to the positional).")
  @Nullable String hashOpt;

  @Option(names = "--env", description = "Limit to one environment (falls back to the persisted 'env' config).")
  @Nullable String env;

  @Option(names = "--since-minutes", description = "Window size in minutes (default: 180).")
  @Nullable Long sinceMinutes;

  @Option(names = "--since-hours", description = "Window size in hours (mutually exclusive with --since-minutes).")
  @Nullable Long sinceHours;

  @Option(names = "--by", defaultValue = "mean",
      description = "Top chart measure: total | mean | max | count (default mean). The lower chart is always calls.")
  Measure by = Measure.mean;

  @Override
  public Integer call() {
    if (sinceMinutes != null && sinceHours != null) {
      throw new CliException("Supply only one of --since-minutes / --since-hours, not both.");
    }
    String app = appOpt != null ? appOpt : appArg;
    if (app == null) {
      app = ConfigDefaults.appOrNull();
    }
    if (app == null) {
      throw new CliException("No application given. Pass it positionally, with --app, or set 'app' in config.");
    }
    String hash = hashOpt != null ? hashOpt : hashArg;
    if (hash == null) {
      throw new CliException("No metric hash given. Pass it positionally or with --hash.");
    }
    if (env == null) {
      env = ConfigDefaults.envOrNull();
    }
    try (Insight insight = Insight.open(conn)) {
      MetricTimeseries ts;
      Long windowMinutes = sinceMinutes;
      if (windowMinutes == null && sinceHours == null) {
        windowMinutes = DEFAULT_TREND_WINDOW_MINUTES;
      }
      try {
        ts = insight.metrics.getMetricTimeseries(app, hash, windowMinutes, sinceHours, env);
      } catch (HttpException e) {
        if (e.statusCode() == 404) {
          throw new CliException("This server build does not serve the metric time-series endpoint yet"
              + " (needs the ix-trend-endpoint server change deployed).");
        }
        throw e;
      }
      if (out.json()) {
        out.printJson(MetricTimeseries.class, ts);
        return 0;
      }
      printTrend(ts, by);
      return ts.buckets().isEmpty() ? 1 : 0;
    }
  }

  /**
   * Default trend window when none is given: 3 hours. The server serves
   * 1-minute buckets for windows up to 3 hours, so this yields ~180 buckets
   * (one column per minute) — see {@link #TARGET_WIDTH}.
   */
  static final long DEFAULT_TREND_WINDOW_MINUTES = 180L;

  /** Rows tall for the mean column chart (the headline series). */
  private static final int MEAN_ROWS = 8;
  /** Rows tall for the secondary call-volume column chart. */
  private static final int CALLS_ROWS = 3;
  /**
   * Target chart width in columns. Width is one column per bucket, capped here:
   * the server's bucket resolution gives ~180 columns for the default 3-hour
   * window (1-minute buckets); longer windows that return more buckets are
   * down-sampled to this width so the chart stays readable.
   */
  static final int TARGET_WIDTH = 180;

  static void printTrend(MetricTimeseries ts) {
    printTrend(ts, Measure.mean);
  }

  static void printTrend(MetricTimeseries ts, Measure by) {
    System.out.println("Trend — " + (ts.label() == null ? ts.hash() : ts.label()) + "  [" + ts.app() + "]");
    System.out.println("window " + ts.windowMinutes() + "m, bucket " + ts.bucketMinutes() + "m, "
        + ts.buckets().size() + " buckets");
    List<MetricTimeBucket> buckets = ts.buckets();
    if (buckets.isEmpty()) {
      System.out.println("  (no data in window)");
      return;
    }
    int n = buckets.size();
    long[] count = new long[n];
    long totalCalls = 0;
    for (int i = 0; i < n; i++) {
      long c = buckets.get(i).count();
      count[i] = c;
      totalCalls += c;
    }
    String callsNote = String.format("total %,d", totalCalls);
    if (by == Measure.count) {
      // Calls IS the chosen measure: render it as the tall headline chart, no duplicate below.
      printColumns("calls", callsNote, TermChart.fit(count, TARGET_WIDTH, TermChart.Agg.SUM), MEAN_ROWS);
      return;
    }
    long[] total = new long[n];
    long[] max = new long[n];
    long grandTotal = 0;
    for (int i = 0; i < n; i++) {
      MetricTimeBucket b = buckets.get(i);
      total[i] = b.total();
      max[i] = b.max();
      grandTotal += total[i];
    }
    // Down-sample first, then derive mean call-weighted (sum totals / sum counts)
    // rather than averaging per-bucket means: an unweighted average would let
    // empty buckets drag the line down, which matters more at fine resolution.
    long[] series;
    long headline;
    switch (by) {
      case total -> {
        series = TermChart.fit(total, TARGET_WIDTH, TermChart.Agg.SUM);
        headline = grandTotal;
      }
      case max -> {
        series = TermChart.fit(max, TARGET_WIDTH, TermChart.Agg.MAX);
        long peak = 0;
        for (long v : series) peak = Math.max(peak, v);
        headline = peak;
      }
      default -> { // mean
        long[] ft = TermChart.fit(total, TARGET_WIDTH, TermChart.Agg.SUM);
        long[] fc = TermChart.fit(count, TARGET_WIDTH, TermChart.Agg.SUM);
        series = new long[ft.length];
        long peak = 0;
        for (int i = 0; i < ft.length; i++) {
          series[i] = fc[i] == 0 ? 0 : Math.floorDiv(ft[i], fc[i]);
          peak = Math.max(peak, series[i]);
        }
        headline = peak;
      }
    }
    String note = by == Measure.total
        ? String.format("total %,d us", headline)
        : String.format("peak %,d us", headline);
    printColumns(by.label(), note, series, MEAN_ROWS);
    printColumns("calls", callsNote, TermChart.fit(count, TARGET_WIDTH, TermChart.Agg.SUM), CALLS_ROWS);
  }

  /**
   * Which per-bucket measure the tall headline trend chart plots. Names match the
   * {@code --by} values on {@code top} / {@code missing-plans} so a drill-down keeps
   * the same measure the list was ranked by. The lower chart is always calls.
   */
  enum Measure {
    total("total (us)"),
    mean("mean (us)"),
    max("max (us)"),
    count("calls");

    private final String label;

    Measure(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }

    /** Map a {@code top}/{@code missing-plans} order-by name (total/mean/max/count) to a measure. */
    static Measure of(String byName) {
      return valueOf(byName);
    }
  }


  /** Print a labelled multi-row column chart: a heading line then {@code height} rows. */
  static void printColumns(String label, String note, long[] series, int height) {
    System.out.println();
    System.out.println("  " + label + "   " + note);
    for (String line : TermChart.columns(series, height)) {
      System.out.println("  " + AnsiColor.chart(line));
    }
  }
}
