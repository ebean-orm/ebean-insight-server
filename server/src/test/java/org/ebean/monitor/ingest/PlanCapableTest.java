package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCapableTest {

  private boolean v1(String name) {
    return PlanCapable.derive(name, null);
  }

  private boolean v2(String kind, String label) {
    Map<String, Object> tags = new LinkedHashMap<>();
    if (kind != null) {
      tags.put("kind", kind);
    }
    if (label != null) {
      tags.put("label", label);
    }
    return PlanCapable.derive("ebean.query", tags);
  }

  @Test
  void planCapable_ormQueries() {
    assertThat(v1("orm.Customer.findList")).isTrue();
    assertThat(v1("orm.OrderDao.findOrdersForPublishing")).isTrue();
    assertThat(v1("orm.Customer.custMain")).isTrue();
    assertThat(v1("orm.Customer.custMain.contacts.lazy")).isTrue();
  }

  @Test
  void notPlanCapable_ormUpdate() {
    assertThat(v1("orm.update.Customer")).isFalse();
    assertThat(v1("orm.update.someLabel")).isFalse();
  }

  @Test
  void planCapable_dtoQueries() {
    assertThat(v1("dto.CustomerDto_byEmail")).isTrue();
    assertThat(v1("dto.DCust.custDtoPlan")).isTrue();
    assertThat(v1("dto.SensorState")).isTrue();
  }

  @Test
  void planCapable_sqlQueries() {
    assertThat(v1("sql.query.findStuff")).isTrue();
    assertThat(v1("sql.query.custSqlPlan")).isTrue();
  }

  @Test
  void notPlanCapable_other() {
    assertThat(v1("sql.update.bulkUpdate")).isFalse();
    assertThat(v1("iud.Customer.insert")).isFalse();
    assertThat(v1("txn.main")).isFalse();
    assertThat(v1("l2n.customer.hit")).isFalse();
    assertThat(v1(null)).isFalse();
  }

  @Test
  void planCapableV2_fromKindTag() {
    assertThat(v2("orm", "Customer.findList")).isTrue();
    assertThat(v2("orm", "Customer.custMain.contacts.lazy")).isTrue();
    assertThat(v2("dto", "SensorState")).isTrue();
    assertThat(v2("sql", "query.findStuff")).isTrue();
  }

  @Test
  void notPlanCapableV2_fromKindTag() {
    assertThat(v2("orm", "update.Customer")).isFalse();
    assertThat(v2("sql", "update.bulkUpdate")).isFalse();
    assertThat(v2("sql", "call.someProc")).isFalse();
    assertThat(v2(null, "User.save")).isFalse();
  }
}
