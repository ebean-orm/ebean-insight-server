package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricCommandsParseTest {

  @Test
  void rootCommand_registersNewSubcommands() {
    var spec = new CommandLine(new InsightCli()).getCommandSpec();
    assertThat(spec.subcommands()).containsKeys("top", "metrics", "metric", "missing-plans");
  }

  @Test
  void top_parsesChartAndInteractive() {
    var cmd = CommandLine.populateCommand(new TopCommand(), "--chart");
    assertThat(cmd.chart).isTrue();
    assertThat(cmd.interactive).isFalse();
    var cmd2 = CommandLine.populateCommand(new TopCommand(), "-i");
    assertThat(cmd2.interactive).isTrue();
  }

  @Test
  void missingPlans_parsesInteractive() {
    var cmd = CommandLine.populateCommand(new MissingPlansCommand(), "-i");
    assertThat(cmd.interactive).isTrue();
  }

  @Test
  void metric_parsesPositionalAndFlagForms() {
    var pos = CommandLine.populateCommand(new MetricCommand(), "myapp", "abc123");
    assertThat(pos.appArg).isEqualTo("myapp");
    assertThat(pos.hashArg).isEqualTo("abc123");
    var flags = CommandLine.populateCommand(new MetricCommand(), "--app", "myapp", "--hash", "abc123");
    assertThat(flags.appOpt).isEqualTo("myapp");
    assertThat(flags.hashOpt).isEqualTo("abc123");
  }

  @Test
  void metric_noHash_fails() {
    var cmd = CommandLine.populateCommand(new MetricCommand(), "myapp");
    assertThatThrownBy(cmd::call)
        .isInstanceOf(CliException.class)
        .hasMessageContaining("hash");
  }

  @Test
  void metric_parsesInteractiveAndEnv() {
    var cmd = CommandLine.populateCommand(new MetricCommand(), "myapp", "abc123", "-i", "--env", "test");
    assertThat(cmd.interactive).isTrue();
    assertThat(cmd.env).isEqualTo("test");
    assertThat(CommandLine.populateCommand(new MetricCommand(), "myapp", "abc123").interactive).isFalse();
  }

  @Test
  void changes_parsesInteractiveAndFilters() {
    var cmd = CommandLine.populateCommand(new ChangesCommand(),
        "--app", "myapp", "--env", "test", "--type", "CHANGED", "--hash", "h1", "-i");
    assertThat(cmd.interactive).isTrue();
    assertThat(cmd.app).isEqualTo("myapp");
    assertThat(cmd.env).isEqualTo("test");
    assertThat(cmd.type).isEqualTo("CHANGED");
    assertThat(cmd.hash).isEqualTo("h1");
    assertThat(CommandLine.populateCommand(new ChangesCommand()).interactive).isFalse();
  }

  @Test
  void trend_parsesPositionalAndFlagForms() {
    var pos = CommandLine.populateCommand(new TrendCommand(), "myapp", "abc123", "--since-hours", "6", "--env", "test");
    assertThat(pos.appArg).isEqualTo("myapp");
    assertThat(pos.hashArg).isEqualTo("abc123");
    assertThat(pos.sinceHours).isEqualTo(6L);
    assertThat(pos.env).isEqualTo("test");
    var flags = CommandLine.populateCommand(new TrendCommand(), "--app", "myapp", "--hash", "abc123");
    assertThat(flags.appOpt).isEqualTo("myapp");
    assertThat(flags.hashOpt).isEqualTo("abc123");
  }

  @Test
  void trend_rejectsBothWindowOptions() {
    var cmd = CommandLine.populateCommand(new TrendCommand(), "myapp", "h", "--since-minutes", "5", "--since-hours", "1");
    assertThatThrownBy(cmd::call)
        .isInstanceOf(CliException.class)
        .hasMessageContaining("only one of --since-minutes / --since-hours");
  }

  @Test
  void rootCommand_registersTrend() {
    var spec = new CommandLine(new InsightCli()).getCommandSpec();
    assertThat(spec.subcommands()).containsKey("trend");
  }

  @Test
  void top_defaults() {
    var cmd = CommandLine.populateCommand(new TopCommand());
    assertThat(cmd.by).isEqualTo("label");
    assertThat(cmd.sort).isEqualTo(TopCommand.Sort.total);
    assertThat(cmd.limit).isEqualTo(20);
    assertThat(cmd.app).isNull();
    assertThat(cmd.env).isNull();
    assertThat(cmd.planCapable).isNull();
  }

  @Test
  void top_parsesOptions() {
    var cmd = CommandLine.populateCommand(new TopCommand(),
        "--app", "central-notifications", "--env", "test", "--by", "type",
        "--sort", "mean", "--since-hours", "2", "-n", "5");
    assertThat(cmd.app).isEqualTo("central-notifications");
    assertThat(cmd.env).isEqualTo("test");
    assertThat(cmd.by).isEqualTo("type");
    assertThat(cmd.sort).isEqualTo(TopCommand.Sort.mean);
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
  void top_invalidSort_failsParsing() {
    var result = new CommandLine(new TopCommand()).execute("--sort", "bananas");
    assertThat(result).isNotZero();
  }

  @Test
  void metrics_requiresApp() {
    var result = new CommandLine(new MetricsCommand()).execute();
    assertThat(result).isNotZero();
  }

  @Test
  void missingPlans_defaults() {
    var cmd = CommandLine.populateCommand(new MissingPlansCommand());
    assertThat(cmd.app).isNull();
    assertThat(cmd.by).isEqualTo(MissingPlansCommand.OrderBy.total);
    assertThat(cmd.limit).isEqualTo(20);
    assertThat(cmd.sinceMinutes).isNull();
    assertThat(cmd.olderThanHours).isNull();
  }

  @Test
  void missingPlans_parsesOptions() {
    var cmd = CommandLine.populateCommand(new MissingPlansCommand(),
        "--app", "central-notifications", "--by", "mean", "--since-hours", "2",
        "--older-than-hours", "24", "-n", "5");
    assertThat(cmd.app).isEqualTo("central-notifications");
    assertThat(cmd.by).isEqualTo(MissingPlansCommand.OrderBy.mean);
    assertThat(cmd.sinceHours).isEqualTo(2L);
    assertThat(cmd.olderThanHours).isEqualTo(24L);
    assertThat(cmd.limit).isEqualTo(5);
  }

  @Test
  void missingPlans_invalidOrderBy_failsParsing() {
    var result = new CommandLine(new MissingPlansCommand()).execute("--by", "bananas");
    assertThat(result).isNotZero();
  }

  @Test
  void missingPlans_captureFlags() {
    var cmd = CommandLine.populateCommand(new MissingPlansCommand(),
        "--app", "myapp", "--capture", "--yes", "--env", "test");
    assertThat(cmd.capture).isTrue();
    assertThat(cmd.yes).isTrue();
    assertThat(cmd.env).isEqualTo("test");
  }

  @Test
  void capture_parsesMultipleHashes() {
    var cmd = CommandLine.populateCommand(new CaptureCommand(),
        "myapp", "h1", "h2", "h3", "--env", "test");
    assertThat(cmd.app).isEqualTo("myapp");
    assertThat(cmd.hashes).containsExactly("h1", "h2", "h3");
    assertThat(cmd.env).isEqualTo("test");
    assertThat(cmd.stdin).isFalse();
  }

  @Test
  void capture_stdinFlag() {
    var cmd = CommandLine.populateCommand(new CaptureCommand(), "myapp", "--stdin");
    assertThat(cmd.stdin).isTrue();
    assertThat(cmd.hashes).isEmpty();
  }

  @Test
  void capture_envBetweenAppAndHashes() {
    var cmd = CommandLine.populateCommand(new CaptureCommand(),
        "myapp", "--env", "test", "hashA", "hashB");
    assertThat(cmd.app).isEqualTo("myapp");
    assertThat(cmd.env).isEqualTo("test");
    assertThat(cmd.hashes).containsExactly("hashA", "hashB");
  }

  @Test
  void capture_flagForms_appAndHash() {
    var cmd = CommandLine.populateCommand(new CaptureCommand(),
        "--app", "myapp", "--hash", "h1", "--hash", "h2", "--env", "test");
    assertThat(cmd.appOption).isEqualTo("myapp");
    assertThat(cmd.hashOptions).containsExactly("h1", "h2");
    assertThat(cmd.app).isNull();
    assertThat(cmd.hashes).isEmpty();
  }

  @Test
  void pending_parsesAppEnv() {
    var cmd = CommandLine.populateCommand(new PendingCommand(), "--app", "myapp", "--env", "test");
    assertThat(cmd.app).isEqualTo("myapp");
    assertThat(cmd.env).isEqualTo("test");
  }

  @Test
  void capture_addTokens_splitsOnCommaAndWhitespace() {
    var set = new java.util.LinkedHashSet<String>();
    CaptureCommand.addTokens(set, "h1,h2 h3,  h4 ,h5");
    assertThat(set).containsExactly("h1", "h2", "h3", "h4", "h5");
  }
}
