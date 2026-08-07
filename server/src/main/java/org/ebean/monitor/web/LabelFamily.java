package org.ebean.monitor.web;

import org.ebean.monitor.v1.model.TopGroup;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Helpers for Ebean's dot-hierarchy label convention: a query label is
 * {@code Type.method} for the originating finder query, followed by zero or
 * more additional dot segments - one per subsequent lazy-loaded fetch path
 * reached while rendering/serialising the result (e.g. {@code
 * CMachine.findByGid.organisationMachines.thirdPartyIdentifiers.query}).
 * <p>
 * Powers the metric-detail page's "query family" tree, which relates a
 * label to its ancestor/descendant fetch-path queries so a slow lazy-load
 * chain can be spotted at a glance.
 */
public final class LabelFamily {

  private LabelFamily() {
  }

  /**
   * The family root for a label: its first two dot segments ({@code
   * Type.method}), or the whole label when it has fewer than two dots (no
   * fetch-path suffix, so no family beyond itself).
   */
  public static String rootOf(String label) {
    final int first = label.indexOf('.');
    if (first < 0) {
      return label;
    }
    final int second = label.indexOf('.', first + 1);
    return second < 0 ? label : label.substring(0, second);
  }

  /** One row of the fully-expanded "query family" tree, in pre-order. */
  public record FamilyNode(String label, String display, int depth,
                           long totalMicros, long meanMicros, long count,
                           double pct, boolean current) {
  }

  /**
   * Build the fully-expanded family tree from a flat set of per-label
   * metric rows (root + every descendant sharing its prefix). Rows are
   * sorted alphabetically, which - since every descendant label textually
   * extends its ancestor - naturally yields pre-order (parent immediately
   * followed by its own descendants); {@code depth} is derived from the
   * number of dot segments past the root, and {@code pct} is each row's
   * total time as a percentage of the row with the largest total in the
   * family, for a simple relative-weight bar (the busiest query in the
   * chain - root or descendant - always fills the bar; every other row is
   * scaled proportionally against it).
   */
  public static List<FamilyNode> buildTree(List<TopGroup> groups, String root, @Nullable String currentLabel) {
    final List<TopGroup> sorted = groups.stream()
      .filter(g -> g.label() != null)
      .sorted(Comparator.comparing(TopGroup::label))
      .toList();

    final long maxTotal = sorted.stream().mapToLong(g -> orZero(g.totalMicros())).max().orElse(0L);

    final List<FamilyNode> nodes = new ArrayList<>(sorted.size());
    for (TopGroup g : sorted) {
      final String label = g.label();
      final int depth = depthOf(root, label);
      final String display = depth == 0 ? label : label.substring(root.length() + 1);
      final long totalMicros = orZero(g.totalMicros());
      final long meanMicros = orZero(g.meanMicros());
      final long count = g.count() == null ? 0L : g.count();
      final double pct = maxTotal <= 0L ? 0.0 : Math.min(100.0, (totalMicros * 100.0) / maxTotal);
      nodes.add(new FamilyNode(label, display, depth, totalMicros, meanMicros, count, pct, label.equals(currentLabel)));
    }
    return nodes;
  }

  /** Number of dot segments {@code label} extends past {@code root} (0 for the root itself). */
  private static int depthOf(String root, String label) {
    if (root.equals(label)) {
      return 0;
    }
    int depth = 0;
    int idx = root.length();
    while ((idx = label.indexOf('.', idx)) >= 0) {
      depth++;
      idx++;
    }
    return depth;
  }

  private static long orZero(@Nullable Long value) {
    return value == null ? 0L : value;
  }
}
