package org.ebean.monitor.forward;

import org.ebean.monitor.api.QueryPlanRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanLoggerTest {

  private static QueryPlanRequest sampleRequest() {
    var req = new QueryPlanRequest();
    req.appName = "consolidation";
    req.environment = "prod";
    var p = new QueryPlanRequest.QPlan();
    p.hash = "abc123";
    p.label = "User.findById";
    p.sql = "select id, name from user where id = ?";
    p.bind = "1";
    p.plan = "Index Scan using user_pk on user";
    p.queryTimeMicros = 1500;
    p.captureCount = 42;
    p.captureMicros = 1850;
    p.whenCaptured = "2025-06-05T02:30:00Z";
    req.plans.add(p);
    return req;
  }

  @Test
  void disabled_isNoOp() {
    var logger = new QueryPlanLogger(false, false);
    assertThat(logger.enabled()).isFalse();
    // log() must not throw with disabled / null / empty input
    logger.log(null);
    logger.log(new QueryPlanRequest());
    logger.log(sampleRequest());
  }

  @Test
  void format_excludesBind_byDefault() {
    var logger = new QueryPlanLogger(true, false);
    var req = sampleRequest();
    String line = logger.format(req, req.plans.get(0));

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
    String line = logger.format(req, req.plans.get(0));
    assertThat(line).contains("bind: 1");
  }

  @Test
  void format_splitsFlatLabelIntoKindAndLabel() {
    var logger = new QueryPlanLogger(true, false);
    var req = sampleRequest();
    var p = req.plans.get(0);
    p.label = "orm.Foo.find";
    String line = logger.format(req, p);
    assertThat(line)
      .contains("name=\"ebean.query\"")
      .contains("kind=\"orm\"")
      .contains("label=\"Foo.find\"");
  }

  @Test
  void format_handlesNullLabelAndEmptyBind() {
    var logger = new QueryPlanLogger(true, true);
    var req = sampleRequest();
    var p = req.plans.get(0);
    p.label = null;
    p.bind = "";
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
