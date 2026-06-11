package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.QueryPlan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Show a single captured query plan. */
@Command(name = "plan", mixinStandardHelpOptions = true, description = "Show a single captured query plan by id.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight plan 12345          # full SQL, bind values and EXPLAIN output",
        "  insight plan 12345 --raw    # EXPLAIN plan text only",
        "  insight plan 12345 -o json",
        "  # find a plan id first:  insight plans --app myapp --env test"
    })
final class PlanCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", description = "The plan id.")
  Long planId;

  @Option(names = "--raw", description = "Print only the raw EXPLAIN plan text (ignored with -o json).")
  boolean raw;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      QueryPlan p = insight.plans.getPlan(planId);
      if (out.json()) {
        out.printJson(QueryPlan.class, p);
        return 0;
      }
      if (raw) {
        System.out.println(p.plan());
        return 0;
      }
      printPlan(p);
      return 0;
    }
  }

  static void printPlan(QueryPlan p) {
    System.out.println("id:        " + p.id());
    System.out.println("hash:      " + p.hash());
    System.out.println("label:     " + p.label());
    System.out.println("env:       " + p.envName());
    System.out.println("queryTime: " + p.queryTimeMicros() + "us");
    System.out.println("captured:  " + p.whenCaptured());
    System.out.println();
    System.out.println("sql:");
    System.out.println(p.sql());
    System.out.println();
    System.out.println("bind: " + p.bind());
    System.out.println();
    System.out.println("plan:");
    System.out.println(p.plan());
    if (p.planShape() != null) {
      System.out.println();
      System.out.println("shape (algo " + p.planShapeAlgo() + ", hash "
          + Interactive.shortShape(p.planShapeHash()) + "):");
      System.out.println(p.planShape());
    }
  }
}
