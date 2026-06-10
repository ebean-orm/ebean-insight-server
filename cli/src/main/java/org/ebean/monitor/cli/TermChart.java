package org.ebean.monitor.cli;

/**
 * Tiny dependency-free terminal chart renderer.
 *
 * <p>Renders proportional horizontal bars and sparklines using Unicode block
 * characters. Pure functions returning strings — no ANSI, no terminal control,
 * no third-party libraries — so it compiles cleanly to a GraalVM native image
 * and degrades gracefully when piped.
 */
final class TermChart {

  /** Eighth-width blocks for sub-character bar precision (index 0..8). */
  private static final char[] EIGHTHS = {' ', '\u258F', '\u258E', '\u258D', '\u258C', '\u258B', '\u258A', '\u2589', '\u2588'};

  /** Eight vertical levels for sparklines. */
  private static final char[] SPARK = {'\u2581', '\u2582', '\u2583', '\u2584', '\u2585', '\u2586', '\u2587', '\u2588'};

  private TermChart() {
  }

  /**
   * Render a proportional horizontal bar of the given width (in characters),
   * with sub-character precision via eighth-blocks.
   *
   * @param value the value to plot
   * @param max   the value mapped to a full-width bar (the row maximum)
   * @param width the bar width in characters
   */
  static String bar(double value, double max, int width) {
    if (width <= 0) {
      return "";
    }
    double frac = (max <= 0 || value <= 0) ? 0.0 : Math.min(1.0, value / max);
    int eighths = (int) Math.round(frac * width * 8.0);
    int full = eighths / 8;
    int rem = eighths % 8;
    StringBuilder sb = new StringBuilder(width);
    for (int i = 0; i < full; i++) {
      sb.append(EIGHTHS[8]);
    }
    if (rem > 0 && full < width) {
      sb.append(EIGHTHS[rem]);
      full++;
    }
    while (full < width) {
      sb.append(' ');
      full++;
    }
    return sb.toString();
  }

  /**
   * Render a single-line sparkline over the values (8 vertical levels), scaled
   * between the series min and max.
   */
  static String sparkline(long[] values) {
    if (values == null || values.length == 0) {
      return "";
    }
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (long v : values) {
      min = Math.min(min, v);
      max = Math.max(max, v);
    }
    long span = max - min;
    StringBuilder sb = new StringBuilder(values.length);
    for (long v : values) {
      int idx = (span == 0) ? 0 : (int) Math.round((double) (v - min) / span * (SPARK.length - 1));
      sb.append(SPARK[Math.max(0, Math.min(SPARK.length - 1, idx))]);
    }
    return sb.toString();
  }

  /** How {@link #fit} combines the adjacent values that fall into one output group. */
  enum Agg {
    /** Add them up — for additive series such as call counts or totals. */
    SUM,
    /** Average them — for mean-time series. */
    AVG,
    /** Take the largest — for max-time series (a peak must not be diluted). */
    MAX
  }

  /**
   * Down-sample a series to at most {@code target} points by aggregating adjacent
   * values into proportional groups. Series at or under {@code target} are returned
   * unchanged (never padded up). Use {@code sum=true} for additive series (call
   * counts) and {@code sum=false} to average (mean times).
   */
  static long[] fit(long[] values, int target, boolean sum) {
    return fit(values, target, sum ? Agg.SUM : Agg.AVG);
  }

  /**
   * Down-sample a series to at most {@code target} points, combining the values in
   * each proportional group with {@code agg}. Series at or under {@code target} are
   * returned unchanged (never padded up).
   */
  static long[] fit(long[] values, int target, Agg agg) {
    if (values == null) {
      return new long[0];
    }
    if (target <= 0 || values.length <= target) {
      return values;
    }
    int n = values.length;
    long[] out = new long[target];
    for (int g = 0; g < target; g++) {
      int from = (int) ((long) g * n / target);
      int to = (int) ((long) (g + 1) * n / target);
      if (to <= from) {
        to = Math.min(n, from + 1);
      }
      long acc = 0;
      long peak = 0;
      int cnt = 0;
      for (int i = from; i < to; i++) {
        acc += values[i];
        peak = Math.max(peak, values[i]);
        cnt++;
      }
      out[g] = switch (agg) {
        case SUM -> acc;
        case MAX -> peak;
        case AVG -> cnt == 0 ? 0 : acc / cnt;
      };
    }
    return out;
  }

  /** Bottom-row glyph for an empty (zero) column, so the x-axis stays a continuous line. */
  private static final char BASELINE = '\u2581';

  /**
   * Render a multi-row vertical column chart over the values: one character-wide
   * column per value, {@code height} rows tall, using vertical eighth-blocks for
   * sub-row precision. Columns are scaled from zero to the series maximum.
   *
   * <p>Empty (zero) columns still draw a {@link #BASELINE} glyph on the bottom row
   * so the x-axis reads as a continuous line rather than leaving gaps.
   *
   * @return {@code height} lines, top row first; each line is {@code values.length} chars wide.
   */
  static String[] columns(long[] values, int height) {
    if (height <= 0) {
      return new String[0];
    }
    int width = (values == null) ? 0 : values.length;
    String[] rows = new String[height];
    long max = 0;
    for (int i = 0; i < width; i++) {
      max = Math.max(max, values[i]);
    }
    // bottom-indexed builders: bottom[0] is the lowest row.
    StringBuilder[] bottom = new StringBuilder[height];
    for (int r = 0; r < height; r++) {
      bottom[r] = new StringBuilder(width);
    }
    int maxEighths = height * 8;
    for (int i = 0; i < width; i++) {
      long v = values[i];
      int level = (max <= 0 || v <= 0) ? 0
          : Math.max(1, (int) Math.round((double) v / max * maxEighths));
      for (int r = 0; r < height; r++) {
        int rem = level - r * 8;
        char c;
        if (rem >= 8) {
          c = SPARK[7]; // full block
        } else if (rem <= 0) {
          // empty cell — but keep a baseline on the bottom row so the axis is continuous
          c = (r == 0) ? BASELINE : ' ';
        } else {
          c = SPARK[rem - 1];
        }
        bottom[r].append(c);
      }
    }
    for (int r = 0; r < height; r++) {
      rows[r] = bottom[height - 1 - r].toString();
    }
    return rows;
  }
}
