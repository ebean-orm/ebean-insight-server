package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class VersionProviderTest {

  @Test
  void getVersion_returnsThreeLines() {
    String[] lines = new VersionProvider().getVersion();
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).startsWith("ebean-insight-cli ");
    assertThat(lines[1]).startsWith("commit: ");
    assertThat(lines[2]).startsWith("built: ");
  }

  @Test
  void getVersion_neverContainsUnsubstitutedPlaceholders() {
    String[] lines = new VersionProvider().getVersion();
    for (String line : lines) {
      assertThat(line).doesNotContain("${");
    }
  }

  @Test
  void cli_versionFlag_printsVersionBanner() {
    var buffer = new StringWriter();
    int exit = new CommandLine(new InsightCli())
        .setOut(new PrintWriter(buffer, true))
        .execute("--version");
    assertThat(exit).isZero();
    String out = buffer.toString();
    assertThat(out)
        .contains("ebean-insight-cli ")
        .contains("commit: ")
        .contains("built: ");
  }
}

