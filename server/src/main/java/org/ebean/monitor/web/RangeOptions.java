package org.ebean.monitor.web;

import org.ebean.monitor.web.view.Option;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared "time range" dropdown options for the dashboard pages
 * ({@code query-total}, {@code metric-detail}) — kept in one place so both
 * pages offer the same set of windows and resolve/format them identically.
 */
final class RangeOptions {

  record RangeOption(String key, String label, int minutes) {
  }

  static RangeOption custom() {
    return new RangeOption("custom", "Custom range", 0);
  }

  static final List<RangeOption> RANGES = List.of(
    new RangeOption("30m", "Last 30 mins", 30),
    new RangeOption("1h", "Last hour", 60),
    new RangeOption("4h", "Last 4 hours", 240),
    new RangeOption("6h", "Last 6 hours", 360),
    new RangeOption("12h", "Last 12 hours", 720),
    new RangeOption("24h", "Last 24 hours", 1440),
    new RangeOption("2d", "Last 2 days", 2880),
    new RangeOption("7d", "Last 7 days", 10080));

  static RangeOption resolve(@Nullable String rangeParam) {
    for (RangeOption candidate : RANGES) {
      if (candidate.key().equals(rangeParam)) {
        return candidate;
      }
    }
    return RANGES.get(0);
  }

  static List<Option> options(String selectedKey) {
    final List<Option> options = RANGES.stream()
      .map(r -> new Option(r.key(), r.label(), r.key().equals(selectedKey)))
      .toList();
    if (!"custom".equals(selectedKey)) {
      return options;
    }
    final List<Option> withCustom = new ArrayList<>(options.size() + 1);
    withCustom.add(new Option("custom", "Custom range", true));
    withCustom.addAll(options);
    return withCustom;
  }

  private RangeOptions() {
  }
}
