package org.ebean.monitor.cli;

import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeCommandsTest {

  @Test
  void rootCommand_registersChangeCommands() {
    var spec = new CommandLine(new InsightCli()).getCommandSpec();
    assertThat(spec.subcommands()).containsKeys("changes", "change");
  }

  @Test
  void changes_parsesOptions() {
    var cmd = CommandLine.populateCommand(new ChangesCommand(),
        "--app", "myapp", "--env", "prod", "--hash", "abc", "--change-type", "CHANGED",
        "--label", "Customer.findList", "--kind", "orm", "--type", "Customer",
        "--since-hours", "24", "-n", "5");
    assertThat(cmd.app).isEqualTo("myapp");
    assertThat(cmd.env).isEqualTo("prod");
    assertThat(cmd.hash).isEqualTo("abc");
    assertThat(cmd.changeType).isEqualTo("CHANGED");
    assertThat(cmd.label).isEqualTo("Customer.findList");
    assertThat(cmd.kind).isEqualTo("orm");
    assertThat(cmd.type).isEqualTo("Customer");
    assertThat(cmd.sinceHours).isEqualTo(24L);
    assertThat(cmd.limit).isEqualTo(5);
  }

  @Test
  void changes_defaults() {
    var cmd = CommandLine.populateCommand(new ChangesCommand());
    assertThat(cmd.app).isNull();
    assertThat(cmd.changeType).isNull();
    assertThat(cmd.limit).isEqualTo(20);
  }

  @Test
  void change_parsesIdAndRaw() {
    var cmd = CommandLine.populateCommand(new ChangeCommand(), "123", "--raw");
    assertThat(cmd.id).isEqualTo(123L);
    assertThat(cmd.raw).isTrue();
  }

  private static PlanChange change(String type, String fromShape, String toShape,
                                   Long fromMicros, long toMicros) {
    return PlanChange.builder()
        .id(7L)
        .appName("myapp")
        .envName("prod")
        .hash("hashabcdef")
        .label("orm.Foo.find")
        .changeType(type)
        .fromPlanId(fromShape == null ? null : 4L)
        .toPlanId(5L)
        .fromShapeHash(fromShape)
        .toShapeHash(toShape)
        .algo(1)
        .fromQueryTimeMicros(fromMicros)
        .toQueryTimeMicros(toMicros)
        .whenCaptured(Instant.parse("2026-07-01T00:01:00Z"))
        .detectedAt(Instant.parse("2026-07-01T00:01:05Z"))
        .build();
  }

  private static QueryPlan plan(long id, String shape, String explain) {
    return QueryPlan.builder()
        .id(id)
        .hash("hashabcdef")
        .label("orm.Foo.find")
        .appMetricId(0L)
        .envName("prod")
        .queryTimeMicros(100L)
        .captureCount(1L)
        .captureMicros(50L)
        .whenCaptured(Instant.parse("2026-07-01T00:01:00Z"))
        .sql("select * from foo where id = ?")
        .bind("[5]")
        .plan(explain)
        .planShape("shape:" + shape)
        .planShapeHash(shape)
        .planShapeAlgo(1)
        .build();
  }

  @Test
  void renderTable_listsRowsNewestFirst() {
    PlanChange changed = change("CHANGED", "AAAAAAAA", "BBBBBBBB", 100L, 250L);
    PlanChange first = change("FIRST", null, "CCCCCCCC", null, 70L);
    String table = ChangesCommand.renderTable(List.of(changed, first));
    assertThat(table).contains("DETECTED", "APP", "ENV", "TYPE", "LABEL", "HASH", "SHAPE", "TIME(us)");
    assertThat(table).contains("CHANGED");
    assertThat(table).contains("FIRST");
    // CHANGED row shows from→to shapes; FIRST row shows just the new shape
    assertThat(table).contains("AAAAAAAA\u2192BBBBBBBB");
    assertThat(table).contains("CCCCCCCC");
  }

  @Test
  void render_changed_showsUnifiedDiff() {
    PlanChangeDetail detail = PlanChangeDetail.builder()
        .change(change("CHANGED", "AAAAAAAA", "BBBBBBBB", 100L, 250L))
        .fromPlan(plan(4L, "AAAAAAAA", "Seq Scan on foo\n  Filter: (id = 5)"))
        .toPlan(plan(5L, "BBBBBBBB", "Index Scan using foo_pk on foo\n  Index Cond: (id = 5)"))
        .build();
    String rendered = ChangeCommand.render(detail);
    assertThat(rendered).contains("change:    CHANGED");
    assertThat(rendered).contains("queryTime: 100us \u2192 250us");
    assertThat(rendered).contains("--- from plan (id 4");
    assertThat(rendered).contains("+++ to plan   (id 5");
    assertThat(rendered).contains("-Seq Scan on foo");
    assertThat(rendered).contains("+Index Scan using foo_pk on foo");
  }

  @Test
  void render_first_noDiff() {
    PlanChangeDetail detail = PlanChangeDetail.builder()
        .change(change("FIRST", null, "CCCCCCCC", null, 70L))
        .toPlan(plan(5L, "CCCCCCCC", "Seq Scan on foo"))
        .build();
    String rendered = ChangeCommand.render(detail);
    assertThat(rendered).contains("change:    FIRST");
    assertThat(rendered).contains("First observed shape");
    assertThat(rendered).doesNotContain("--- from plan");
  }

  @Test
  void unifiedDiff_marksAddedRemovedAndContext() {
    List<String> diff = ChangeCommand.unifiedDiff("a\nb\nc", "a\nx\nc");
    assertThat(diff).containsExactly(" a", "-b", "+x", " c");
  }
}
