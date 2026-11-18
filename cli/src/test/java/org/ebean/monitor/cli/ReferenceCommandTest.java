package org.ebean.monitor.cli;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceCommandTest {

  @Test
  void rootHelpAdvertisesReference() {
    var result = run("--help");

    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("reference")
        .contains("Show a comprehensive reference of all commands and options.")
        .contains("Run 'insight reference' for the full command and option reference.");
  }

  @Test
  void referencePrintsNestedCommandsAndSharedOptions() {
    var result = run("reference");

    assertThat(result.exitCode()).isZero();
    assertThat(result.error()).isEmpty();
    assertThat(result.output())
        .startsWith("# insight reference")
        .contains("## insight config")
        .contains("### insight config set KEY VALUE")
        .contains("### insight top")
        .contains("### insight metrics --app=<app> [options]")
        .contains("Aliases: `ls`")
        .contains("total, mean, max, count, value")
        .contains("(default: total)")
        .contains("## Common connection options")
        .contains("## Common output options")
        .contains("--url=<url>")
        .doesNotContain("## insight reference")
        .doesNotContain("${");

    assertThat(result.output().lines().filter(line -> line.contains("--url=<url>")).count())
        .isEqualTo(1);
  }

  @Test
  void referenceFiltersByTopLevelCommand() {
    var result = run("reference", "config");

    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("## insight config")
        .contains("### insight config set")
        .doesNotContain("## Common connection options")
        .doesNotContain("## Common output options")
        .doesNotContain("## insight top");
  }

  @Test
  void referenceUnknownCommandReturnsCliError() {
    var result = run("reference", "unknown");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.error())
        .contains("error: Unknown command 'unknown'. Available commands:");
  }

  @Test
  void helpFlagBeforeReferencePrintsFullReference() {
    var reference = run("reference");
    var viaHelpFlag = run("--help", "reference");

    assertThat(viaHelpFlag.exitCode()).isZero();
    assertThat(viaHelpFlag.output()).isEqualTo(reference.output());
  }

  private static RunResult run(String... args) {
    var output = new StringWriter();
    var error = new StringWriter();
    int exitCode = InsightCli.commandLine()
        .setOut(new PrintWriter(output, true))
        .setErr(new PrintWriter(error, true))
        .execute(args);
    return new RunResult(exitCode, output.toString(), error.toString());
  }

  private record RunResult(int exitCode, String output, String error) {
  }
}
