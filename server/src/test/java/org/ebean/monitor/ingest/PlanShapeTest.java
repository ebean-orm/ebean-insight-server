package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlanShapeTest {

  private static String fixture(String name) {
    try (var in = PlanShapeTest.class.getResourceAsStream("/planshape/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void plan5_skeleton_exact() {
    var fp = PlanShape.fingerprint(fixture("plan5.txt"));
    assertThat(fp).isNotNull();
    assertThat(fp.skeleton()).isEqualTo(String.join("\n",
      "0|Limit",
      "1|Merge Append",
      "1|key:t0.event_timestamp DESC",
      "2|Index Scan Backward using idx_machine_event_timestamp_type_parent on central.ebox_event t0_1",
      "2|cond:((t0_1.machine_id = ?) and (t0_1.event_timestamp > ?) and (t0_1.event_timestamp <= ?))",
      "2|cond:((t0_1.gps_accuracy < ?) or (t0_1.gps_accuracy is null))",
      "2|Index Scan Backward using idx_machine_event_timestamp_type_" + PlanShape.TOK_MONTH
        + " on central.ebox_event_" + PlanShape.TOK_MONTH + " t0_2",
      "2|cond:((t0_2.machine_id = ?) and (t0_2.event_timestamp > ?) and (t0_2.event_timestamp <= ?))",
      "2|cond:((t0_2.gps_accuracy < ?) or (t0_2.gps_accuracy is null))"
    ));
  }

  @Test
  void plan8_structure() {
    var fp = PlanShape.fingerprint(fixture("plan8.txt"));
    assertThat(fp).isNotNull();
    // node tree (Sort + Append + Seq Scan parent + Index Scan partition child)
    assertThat(fp.skeleton())
      .contains("0|Limit")
      .contains("1|Sort")
      .contains("1|key:t0.event_timestamp DESC")
      .contains("2|Append")
      .contains("3|Seq Scan on central.ebox_event t0_1")
      .contains("3|Index Scan Backward using idx_machine_event_timestamp_type_" + PlanShape.TOK_MONTH
        + " on central.ebox_event_" + PlanShape.TOK_MONTH + " t0_2");
    // the Seq Scan parent Filter is masked (no literals leak)
    assertThat(fp.skeleton())
      .doesNotContain("2543562")
      .doesNotContain("25)")
      .contains("(t0_1.machine_id = ?)");
  }

  @Test
  void samePlanHash_butStructurallyDifferent_detectedAsChange() {
    // plan5 and plan8 share the query hash a94c62c88a21... but differ structurally
    var a = PlanShape.fingerprint(fixture("plan5.txt"));
    var b = PlanShape.fingerprint(fixture("plan8.txt"));
    assertThat(a.hash()).isNotEqualTo(b.hash());
  }

  @Test
  void monthRollover_sameShape() {
    // The same plan captured a month later: partition suffix and date literals move,
    // but the canonicalised shape (and hash) must be identical.
    String june = fixture("plan5.txt");
    String july = june.replace("202606", "202607")
      .replace("2026-06", "2026-07");
    assertThat(PlanShape.fingerprint(july).hash())
      .isEqualTo(PlanShape.fingerprint(june).hash());
  }

  @Test
  void shapeIsDeterministic() {
    var a = PlanShape.fingerprint(fixture("plan10.txt"));
    var b = PlanShape.fingerprint(fixture("plan10.txt"));
    assertThat(a.hash()).isEqualTo(b.hash());
    assertThat(a.hash()).hasSize(64);
  }

  @Test
  void noBindLiteralsOrNoiseLeakIntoShape() {
    var fp = PlanShape.fingerprint(fixture("plan9.txt"));
    assertThat(fp.skeleton())
      .doesNotContain("cost=")
      .doesNotContain("actual")
      .doesNotContain("Buffers")
      .doesNotContain("Output:")
      .doesNotContain("MarksMegaTrucks1556594009333")
      .doesNotContain("rows=");
  }

  @Test
  void joinTreeRetainsTablesAndPredicates() {
    var fp = PlanShape.fingerprint(fixture("plan10.txt"));
    assertThat(fp.skeleton())
      .contains("on central.organisation_machine_has_services int_")
      .contains("on central.service_plan t0")
      .contains("cond:(int_.organisation_machine_id = any (?))")
      .contains("cond:(t0.id = int_.service_id)");
  }

  @Test
  void placeholderOrBlank_returnsNull() {
    assertThat(PlanShape.fingerprint(null)).isNull();
    assertThat(PlanShape.fingerprint("")).isNull();
    assertThat(PlanShape.fingerprint("   ")).isNull();
    assertThat(PlanShape.fingerprint("QUERY PLAN\n")).isNull();
  }

  @Test
  void partition_canonicalisation_granularities() {
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_202606"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_MONTH);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_2026_06"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_MONTH);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_20260611"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_DAY);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_2026_06_11"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_DAY);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_2026"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_YEAR);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_2026w23"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_WEEK);
    assertThat(PlanShape.canonicalizeIdentifiers("central.ebox_event_2026_w23"))
      .isEqualTo("central.ebox_event_" + PlanShape.TOK_WEEK);
    assertThat(PlanShape.canonicalizeIdentifiers("idx_evt_p202606"))
      .isEqualTo("idx_evt_" + PlanShape.TOK_MONTH);
  }

  @Test
  void partition_canonicalisation_negatives() {
    assertThat(PlanShape.canonicalizeIdentifiers("idx_machine_event_timestamp_type_parent"))
      .isEqualTo("idx_machine_event_timestamp_type_parent");
    assertThat(PlanShape.canonicalizeIdentifiers("central.iso_3166"))
      .isEqualTo("central.iso_3166");
    assertThat(PlanShape.canonicalizeIdentifiers("central.oauth2"))
      .isEqualTo("central.oauth2");
    assertThat(PlanShape.canonicalizeIdentifiers("central.address_v2"))
      .isEqualTo("central.address_v2");
    assertThat(PlanShape.canonicalizeIdentifiers("t0_2"))
      .isEqualTo("t0_2");
  }

  @Test
  void schemeChange_monthlyToDaily_detected() {
    String monthly = PlanShape.canonicalizeIdentifiers("central.ebox_event_202606");
    String daily = PlanShape.canonicalizeIdentifiers("central.ebox_event_20260611");
    assertThat(monthly).isNotEqualTo(daily);
  }

  @Test
  void maskPredicate_masksLiteralsParamsAndArrays() {
    assertThat(PlanShape.maskPredicate(
      "((t0.org_id = $1) AND (t0.event_timestamp >= '2026-06-01 00:00:00+00'::timestamp without time zone))"))
      .isEqualTo("((t0.org_id = ?) and (t0.event_timestamp >= ?))");
    assertThat(PlanShape.maskPredicate("(int_.organisation_machine_id = ANY ('{2451997}'::bigint[]))"))
      .isEqualTo("(int_.organisation_machine_id = any (?))");
    assertThat(PlanShape.maskPredicate("(t0.gps_accuracy < 25)"))
      .isEqualTo("(t0.gps_accuracy < ?)");
  }
}
