package org.ebean.monitor.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Show a single plan-change event, diffing the from/to EXPLAIN plans. */
@Command(name = "change", mixinStandardHelpOptions = true,
    description = "Show one plan-change event: from/to plans and a unified EXPLAIN diff.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight change 123           # summary + unified diff of from/to EXPLAIN",
        "  insight change 123 --raw     # to-plan EXPLAIN text only",
        "  insight change 123 -o json   # full from/to plans as JSON",
        "  # find a change id first:  insight changes --type CHANGED"
    })
final class ChangeCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", description = "The plan-change event id.")
  Long id;

  @Option(names = "--raw", description = "Print only the raw to-plan EXPLAIN text (ignored with -o json).")
  boolean raw;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      PlanChangeDetail detail = insight.plans.getPlanChange(id);
      if (out.json()) {
        out.printJson(PlanChangeDetail.class, detail);
        return 0;
      }
      if (raw) {
        System.out.println(detail.toPlan().plan());
        return 0;
      }
      System.out.print(render(detail));
      return 0;
    }
  }

  static String render(PlanChangeDetail detail) {
    PlanChange c = detail.change();
    StringBuilder sb = new StringBuilder();
    sb.append("change:    ").append(c.changeType()).append('\n');
    sb.append("app:       ").append(c.appName()).append('\n');
    sb.append("env:       ").append(c.envName()).append('\n');
    sb.append("label:     ").append(c.label() == null ? "" : c.label()).append('\n');
    sb.append("hash:      ").append(c.hash()).append('\n');
    sb.append("detected:  ").append(c.detectedAt()).append('\n');
    if (c.fromShapeHash() == null) {
      sb.append("shape:     ").append(Interactive.shortShape(c.toShapeHash()))
          .append("  (algo ").append(c.algo()).append(")\n");
      sb.append("queryTime: ").append(c.toQueryTimeMicros()).append("us\n");
    } else {
      sb.append("shape:     ").append(Interactive.shortShape(c.fromShapeHash()))
          .append(" \u2192 ").append(Interactive.shortShape(c.toShapeHash()))
          .append("  (algo ").append(c.algo()).append(")\n");
      sb.append("queryTime: ")
          .append(c.fromQueryTimeMicros() == null ? "?" : c.fromQueryTimeMicros() + "us")
          .append(" \u2192 ").append(c.toQueryTimeMicros()).append("us\n");
    }
    sb.append('\n');
    sb.append("sql:\n").append(nullToEmpty(detail.toPlan().sql())).append("\n\n");

    QueryPlan from = detail.fromPlan();
    QueryPlan to = detail.toPlan();
    if (from == null) {
      sb.append("First observed shape \u2014 no prior plan to diff.\n\n");
      sb.append("plan:\n").append(nullToEmpty(to.plan())).append('\n');
      return sb.toString();
    }
    sb.append("--- from plan (id ").append(from.id())
        .append(", shape ").append(Interactive.shortShape(from.planShapeHash())).append(")\n");
    sb.append("+++ to plan   (id ").append(to.id())
        .append(", shape ").append(Interactive.shortShape(to.planShapeHash())).append(")\n");
    for (String line : unifiedDiff(nullToEmpty(from.plan()), nullToEmpty(to.plan()))) {
      sb.append(colorize(line)).append('\n');
    }
    return sb.toString();
  }

  private static String colorize(String diffLine) {
    if (diffLine.startsWith("+")) {
      return AnsiColor.added(diffLine);
    }
    if (diffLine.startsWith("-")) {
      return AnsiColor.removed(diffLine);
    }
    return diffLine;
  }

  /**
   * Produce a unified line diff of {@code fromText} → {@code toText} via an LCS
   * backtrack. Unchanged lines are prefixed with a space, removals with
   * {@code -}, additions with {@code +}. Native-image safe (no external deps).
   */
  static List<String> unifiedDiff(String fromText, String toText) {
    String[] a = fromText.split("\n", -1);
    String[] b = toText.split("\n", -1);
    int n = a.length;
    int m = b.length;
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    List<String> outLines = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (a[i].equals(b[j])) {
        outLines.add(" " + a[i]);
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        outLines.add("-" + a[i]);
        i++;
      } else {
        outLines.add("+" + b[j]);
        j++;
      }
    }
    while (i < n) {
      outLines.add("-" + a[i++]);
    }
    while (j < m) {
      outLines.add("+" + b[j++]);
    }
    return outLines;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
