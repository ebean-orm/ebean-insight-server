package org.ebean.monitor.forward;

import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.api.QueryPlanRequest$QPlanBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanLoggerTest {

  private static QueryPlanRequest$QPlanBuilder samplePlanBuilder() {
    return QueryPlanRequest.QPlan.builder()
      .hash("abc123")
      .label("User.findById")
      .sql("select id, name from user where id = ?")
      .bind("1")
      .plan("Index Scan using user_pk on user")
      .queryTimeMicros(1500)
      .captureCount(42)
      .captureMicros(1850)
      .whenCaptured("2025-06-05T02:30:00Z");
  }

  private static QueryPlanRequest requestWith(QueryPlanRequest.QPlan plan) {
    var req = QueryPlanRequest.builder()
      .appName("consolidation")
      .environment("prod")
      .build();
    req.plans().add(plan);
    return req;
  }

  private static QueryPlanRequest sampleRequest() {
    return requestWith(samplePlanBuilder().build());
  }

  @Test
  void disabled_isNoOp() {
    var logger = new QueryPlanLogger(false, false);
    assertThat(logger.enabled()).isFalse();
    // log() must not throw with disabled / null / empty input
    logger.log(null);
    logger.log(QueryPlanRequest.builder().build());
    logger.log(sampleRequest());
  }

  @Test
  void format_excludesBind_byDefault() {
    var logger = new QueryPlanLogger(true, false);
    var req = sampleRequest();
    String line = logger.format(req, req.plans().get(0));

    assertThat(line)
      .contains("QUERYPLAN")
      .contains("app=consolidation")
      .contains("env=prod")
      .contains("hash=abc123")
      .contains("captureMicros=1850")
      .contains("captureCount=42")
      .contains("queryTimeMicros=1500")
      .contains("label=\"User.findById\"")
      .contains("name=\"ebean.query\"")
      .contains("whenCaptured=2025-06-05T02:30:00Z")
      .contains("sql: select id, name from user where id = ?")
      .contains("plan:\nIndex Scan using user_pk on user")
      .doesNotContain("bind:");
  }

  @Test
  void format_includesBind_whenEnabled() {
    var logger = new QueryPlanLogger(true, true);
    var req = sampleRequest();
    String line = logger.format(req, req.plans().get(0));
    assertThat(line).contains("bind: 1");
  }

  @Test
  void format_splitsFlatLabelIntoKindAndLabel() {
    var logger = new QueryPlanLogger(true, false);
    var p = samplePlanBuilder().label("orm.Foo.find").build();
    var req = requestWith(p);
    String line = logger.format(req, p);
    assertThat(line)
      .contains("name=\"ebean.query\"")
      .contains("kind=\"orm\"")
      .contains("label=\"Foo.find\"");
  }

  @Test
  void format_prefersExplicitKindAndType() {
    var logger = new QueryPlanLogger(true, false);
    var p = samplePlanBuilder().kind("orm").type("Customer").label("Customer.findList").build();
    var req = requestWith(p);
    String line = logger.format(req, p);
    assertThat(line)
      .contains("name=\"ebean.query\"")
      .contains("kind=\"orm\"")
      .contains("type=\"Customer\"")
      .contains("label=\"Customer.findList\"");
  }

  @Test
  void format_handlesNullLabelAndEmptyBind() {
    var logger = new QueryPlanLogger(true, true);
    var p = samplePlanBuilder().label(null).bind("").build();
    var req = requestWith(p);
    String line = logger.format(req, p);
    assertThat(line)
      .contains("label=\"\"")
      .doesNotContain("bind:");
  }

  @Test
  void log_enabled_doesNotThrowOnRealLogger() {
    var logger = new QueryPlanLogger(true, true);
    assertThat(logger.enabled()).isTrue();
    logger.log(sampleRequest()); // exercises planLog.info path
  }
}
