package org.ebean.monitor.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserUsageServiceTest {

  @Test
  void onlyUiAndApiPathsAreTracked() {
    assertThat(UserUsageService.isTrackedPath("/v1/apps")).isTrue();
    assertThat(UserUsageService.isTrackedPath("/ux/usage")).isTrue();
    assertThat(UserUsageService.isTrackedPath("/health")).isFalse();
    assertThat(UserUsageService.isTrackedPath("/api/ingest")).isFalse();
    assertThat(UserUsageService.isTrackedPath("/static/index.css")).isFalse();
    assertThat(UserUsageService.isTrackedPath("/api/cli-config")).isFalse();
    assertThat(UserUsageService.isTrackedPath("/ux/app-config")).isFalse();
  }

  @Test
  void aggregateCardinalityIsBounded() {
    var service = new UserUsageService(null, false, 60, 2);

    service.record("user-1", "GET", "/v1/apps/one", 10);
    service.record("user-2", "GET", "/v1/apps/two", 20);
    service.record("user-3", "GET", "/v1/apps/three", 30);

    assertThat(service.activeAggregateCount()).isEqualTo(2);
    assertThat(service.activeRequestCount()).isEqualTo(2);
  }

  @Test
  void forwardOnlyDoesNotAggregate() {
    var service = new UserUsageService(null, true, 60, 10);

    service.record("user-1", "GET", "/v1/apps", 10);

    assertThat(service.activeAggregateCount()).isZero();
  }
}
