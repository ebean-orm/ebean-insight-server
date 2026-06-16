package org.ebean.monitor.web;

import org.ebean.monitor.api.MetricRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTest {

  private static MetricRequest poll(String app, String env) {
    return MetricRequest.builder()
      .appName(app)
      .environment(env)
      .build();
  }

  @Test
  void envSpecific_deliveredOnlyToMatchingEnv() {
    var service = new MessageService();
    service.pushMessage("app", "test", "qp:hashA");

    // a different env does not pick it up
    assertThat(service.responseBody(poll("app", "prod"))).isNull();
    // matching env picks it up
    assertThat(service.responseBody(poll("app", "test"))).isEqualTo("v1|qp:hashA");
    // and it is drained
    assertThat(service.responseBody(poll("app", "test"))).isNull();
  }

  @Test
  void anyEnv_deliveredToWhicheverEnvPollsFirst() {
    var service = new MessageService();
    service.pushMessage("app", MessageService.ANY_ENV, "qp:hashA");

    // an app reporting a concrete environment still receives the any-env message
    assertThat(service.responseBody(poll("app", "prod"))).isEqualTo("v1|qp:hashA");
    assertThat(service.responseBody(poll("app", "prod"))).isNull();
  }

  @Test
  void mergesExactAndAnyBuckets() {
    var service = new MessageService();
    service.pushMessage("app", "test", "qp:exact");
    service.pushMessage("app", MessageService.ANY_ENV, "qp:any");

    String body = service.responseBody(poll("app", "test"));
    assertThat(body).startsWith("v1|").contains("qp:exact").contains("qp:any");
    assertThat(service.pendingResponse()).isFalse();
  }

  @Test
  void anyEnvDoesNotLeakToOtherApps() {
    var service = new MessageService();
    service.pushMessage("app", MessageService.ANY_ENV, "qp:hashA");

    assertThat(service.responseBody(poll("other", "test"))).isNull();
    assertThat(service.responseBody(poll("app", "test"))).isEqualTo("v1|qp:hashA");
  }
}
