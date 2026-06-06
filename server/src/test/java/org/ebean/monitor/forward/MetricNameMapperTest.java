package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricNameMapperTest {

  @Test
  void iud() {
    var m = MetricNameMapper.map("iud.User.save");
    assertThat(m.name()).isEqualTo("ebean.dml");
    assertThat(m.attrs()).containsExactly("label", "User.save");
  }

  @Test
  void orm() {
    var m = MetricNameMapper.map("orm.User.findById");
    assertThat(m.name()).isEqualTo("ebean.query");
    assertThat(m.attrs()).containsExactly("type", "orm", "label", "User.findById");
  }

  @Test
  void dto() {
    var m = MetricNameMapper.map("dto.SensorState");
    assertThat(m.name()).isEqualTo("ebean.query");
    assertThat(m.attrs()).containsExactly("type", "dto", "label", "SensorState");
  }

  @Test
  void sql() {
    var m = MetricNameMapper.map("sql.adhoc");
    assertThat(m.name()).isEqualTo("ebean.query");
    assertThat(m.attrs()).containsExactly("type", "sql", "label", "adhoc");
  }

  @Test
  void txn_named() {
    var m = MetricNameMapper.map("txn.named.MapDailyMachine.run");
    assertThat(m.name()).isEqualTo("ebean.txn");
    assertThat(m.attrs()).containsExactly("label", "MapDailyMachine.run");
  }

  @Test
  void txn_anon() {
    var m = MetricNameMapper.map("txn.someTx");
    assertThat(m.name()).isEqualTo("ebean.txn");
    assertThat(m.attrs()).containsExactly("label", "someTx");
  }

  @Test
  void l2_regionAndOp() {
    var m = MetricNameMapper.map("l2.User.hit");
    assertThat(m.name()).isEqualTo("ebean.l2");
    assertThat(m.attrs()).containsExactly("op", "hit", "region", "User");
  }

  @Test
  void l2_opOnly() {
    var m = MetricNameMapper.map("l2.miss");
    assertThat(m.name()).isEqualTo("ebean.l2");
    assertThat(m.attrs()).containsExactly("op", "miss");
  }

  @Test
  void passthrough_jvm() {
    var m = MetricNameMapper.map("jvm.memory.heap.used");
    assertThat(m.name()).isEqualTo("jvm.memory.heap.used");
    assertThat(m.attrs()).isEmpty();
  }

  @Test
  void passthrough_datasource() {
    var m = MetricNameMapper.map("datasource.pool.size");
    assertThat(m.name()).isEqualTo("datasource.pool.size");
    assertThat(m.attrs()).isEmpty();
  }

  @Test
  void emptyOrNull() {
    assertThat(MetricNameMapper.map(null).name()).isEqualTo("ebean.other");
    assertThat(MetricNameMapper.map("").name()).isEqualTo("ebean.other");
  }

  @Test
  void noDot() {
    var m = MetricNameMapper.map("flat");
    assertThat(m.name()).isEqualTo("flat");
  }
}
