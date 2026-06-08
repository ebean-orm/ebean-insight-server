package org.ebean.monitor.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DAppMetricTest {

  private boolean planCapable(String name) {
    return new DAppMetric(null, "key", name).isPlanCapable();
  }

  @Test
  void planCapable_ormQueries() {
    assertThat(planCapable("orm.Customer.findList")).isTrue();
    assertThat(planCapable("orm.OrderDao.findOrdersForPublishing")).isTrue();
    assertThat(planCapable("orm.Customer.custMain")).isTrue();
    assertThat(planCapable("orm.Customer.custMain.contacts.lazy")).isTrue();
  }

  @Test
  void notPlanCapable_ormUpdate() {
    assertThat(planCapable("orm.update.Customer")).isFalse();
    assertThat(planCapable("orm.update.someLabel")).isFalse();
  }

  @Test
  void planCapable_dtoQueries() {
    assertThat(planCapable("dto.CustomerDto_byEmail")).isTrue();
    assertThat(planCapable("dto.DCust.custDtoPlan")).isTrue();
    assertThat(planCapable("dto.SensorState")).isTrue();
  }

  @Test
  void planCapable_sqlQueries() {
    assertThat(planCapable("sql.query.findStuff")).isTrue();
    assertThat(planCapable("sql.query.custSqlPlan")).isTrue();
  }

  @Test
  void notPlanCapable_other() {
    assertThat(planCapable("sql.update.bulkUpdate")).isFalse();
    assertThat(planCapable("iud.Customer.insert")).isFalse();
    assertThat(planCapable("txn.main")).isFalse();
    assertThat(planCapable("l2n.customer.hit")).isFalse();
    assertThat(planCapable(null)).isFalse();
  }
}
