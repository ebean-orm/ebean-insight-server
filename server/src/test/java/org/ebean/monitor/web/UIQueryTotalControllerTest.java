package org.ebean.monitor.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UIQueryTotalControllerTest {

  @Test
  void metricDetailUrlPreservesAbsoluteRange() {
    final String url = UIQueryTotalController.metricDetailUrl(
      "orders app",
      "prod",
      "custom",
      "Order.findList",
      Instant.parse("2026-08-11T02:00:00Z"),
      Instant.parse("2026-08-11T03:00:00Z"));

    assertThat(url).isEqualTo(
      "/ux/metric-detail?app=orders+app&range=custom&label=Order.findList"
        + "&env=prod&from=2026-08-11T02%3A00%3A00Z&to=2026-08-11T03%3A00%3A00Z");
  }

  @Test
  void queryPlanUrlPreservesAbsoluteRange() {
    final String url = UIQueryTotalController.queryPlanUrl(
      42L,
      "custom",
      Instant.parse("2026-08-11T02:00:00Z"),
      Instant.parse("2026-08-11T03:00:00Z"));

    assertThat(url).isEqualTo(
      "/ux/query-plan?id=42&range=custom"
        + "&from=2026-08-11T02%3A00%3A00Z&to=2026-08-11T03%3A00%3A00Z");
  }
}
