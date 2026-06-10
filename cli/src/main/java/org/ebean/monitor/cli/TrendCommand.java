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

  @Option(names = "--since-minutes", description = "Window size in minutes (default: 60).")
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
      try {
        ts = insight.metrics.getMetricTimeseries(app, hash, sinceMinutes, sinceHours, env);
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

  /** Rows tall for the mean column chart (the headline series). */
  private static final int MEAN_ROWS = 8;
  /** Rows tall for the secondary call-volume column chart. */
  private static final int CALLS_ROWS = 3;
  /**
   * Target chart width in columns. Width is one column per bucket, capped here:
   * the server's bucket resolution gives ~60 columns for the default 60-minute
   * window (1-minute buckets); longer windows that return more buckets are
   * down-sampled to this width so the chart stays ~60 wide and readable.
   */
  static final int TARGET_WIDTH = 60;

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
      long c = buckets.get(i).count() == null ? 0 : buckets.get(i).count();
      count[i] = c;
      totalCalls += c;
    }
    String callsNote = String.format("total %,d", totalCalls);
    if (by == Measure.count) {
      // Calls IS the chosen measure: render it as the tall headline chart, no duplicate below.
      printColumns("calls", callsNote, TermChart.fit(count, TARGET_WIDTH, TermChart.Agg.SUM), MEAN_ROWS);
      return;
    }
    long[] series = new long[n];
    long headline = 0;
    for (int i = 0; i < n; i++) {
      MetricTimeBucket b = buckets.get(i);
      long c = b.count() == null ? 0 : b.count();
      long t = b.total() == null ? 0 : b.total();
      long m = b.max() == null ? 0 : b.max();
      long v = switch (by) {
        case total -> t;
        case max -> m;
        default -> c == 0 ? 0 : Math.floorDiv(t, c); // mean
      };
      series[i] = v;
      headline = by == Measure.total ? headline + v : Math.max(headline, v);
    }
    String note = by == Measure.total
        ? String.format("total %,d us", headline)
        : String.format("peak %,d us", headline);
    printColumns(by.label(), note, TermChart.fit(series, TARGET_WIDTH, by.agg()), MEAN_ROWS);
    printColumns("calls", callsNote, TermChart.fit(count, TARGET_WIDTH, TermChart.Agg.SUM), CALLS_ROWS);
  }

  /**
   * Which per-bucket measure the tall headline trend chart plots. Names match the
   * {@code --by} values on {@code top} / {@code missing-plans} so a drill-down keeps
   * the same measure the list was ranked by. The lower chart is always calls.
   */
  enum Measure {
    total("total (us)", TermChart.Agg.SUM),
    mean("mean (us)", TermChart.Agg.AVG),
    max("max (us)", TermChart.Agg.MAX),
    count("calls", TermChart.Agg.SUM);

    private final String label;
    private final TermChart.Agg agg;

    Measure(String label, TermChart.Agg agg) {
      this.label = label;
      this.agg = agg;
    }

    String label() {
      return label;
    }

    TermChart.Agg agg() {
      return agg;
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
