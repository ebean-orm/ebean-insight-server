package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricCommandsParseTest {

  @Test
  void rootCommand_registersNewSubcommands() {
    var spec = new CommandLine(new InsightCli()).getCommandSpec();
    assertThat(spec.subcommands()).containsKeys("top", "metrics", "missing-plans");
  }

  @Test
  void top_defaults() {
    var cmd = CommandLine.populateCommand(new TopCommand());
    assertThat(cmd.by).isEqualTo(TopCommand.OrderBy.total);
    assertThat(cmd.limit).isEqualTo(20);
    assertThat(cmd.app).isNull();
    assertThat(cmd.planCapable).isNull();
  }

  @Test
  void top_parsesOptions() {
    var cmd = CommandLine.populateCommand(new TopCommand(),
        "--app", "central-notifications", "--by", "mean", "--since-hours", "2", "-n", "5");
    assertThat(cmd.app).isEqualTo("central-notifications");
    assertThat(cmd.by).isEqualTo(TopCommand.OrderBy.mean);
    assertThat(cmd.sinceHours).isEqualTo(2L);
    assertThat(cmd.sinceMinutes).isNull();
    assertThat(cmd.limit).isEqualTo(5);
  }

  @Test
  void top_planCapable_isTriState() {
    assertThat(CommandLine.populateCommand(new TopCommand()).planCapable).isNull();
    assertThat(CommandLine.populateCommand(new TopCommand(), "--plan-capable").planCapable).isTrue();
    assertThat(CommandLine.populateCommand(new TopCommand(), "--plan-capable=false").planCapable).isFalse();
  }

  @Test
  void top_rejectsBothWindowOptions() {
    var cmd = CommandLine.populateCommand(new TopCommand(), "--since-minutes", "5", "--since-hours", "1");
    assertThatThrownBy(cmd::call)
        .isInstanceOf(CliException.class)
        .hasMessageContaining("only one of --since-minutes / --since-hours");
  }

  @Test
  void top_invalidOrderBy_failsParsing() {
    var result = new CommandLine(new TopCommand()).execute("--by", "bananas");
    assertThat(result).isNotZero();
  }

  @Test
  void metrics_requiresApp() {
    var result = new CommandLine(new MetricsCommand()).execute();
    assertThat(result).isNotZero();
  }

  @Test
  void missingPlans_requiresApp() {
    var result = new CommandLine(new MissingPlansCommand()).execute();
    assertThat(result).isNotZero();
  }
}
