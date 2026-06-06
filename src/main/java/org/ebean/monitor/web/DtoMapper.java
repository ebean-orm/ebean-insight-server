package org.ebean.monitor.web;

import org.ebean.monitor.api.QueryPlan;
import org.ebean.monitor.api.QueryPlanSummary;
import org.ebean.monitor.domain.DQueryPlan;

/**
 * Conversions from internal domain entities to public API DTOs.
 * <p>
 * Static-only utility shared by {@link ApiController} and (future)
 * {@code V1Controller}.
 */
final class DtoMapper {

  private DtoMapper() {
  }

  static QueryPlan toDto(DQueryPlan p) {
    QueryPlan dto = new QueryPlan();
    dto.id = p.getId();
    dto.hash = p.hash();
    dto.label = p.label();
    dto.appMetricId = p.metric() == null ? 0L : p.metric().getId();
    dto.envName = p.env().getName();
    dto.queryTimeMicros = p.queryTimeMicros();
    dto.captureCount = p.captureCount();
    dto.captureMicros = p.captureMicros();
    dto.whenCaptured = p.whenCaptured();
    dto.sql = p.sql();
    dto.bind = p.bind();
    dto.plan = p.plan();
    return dto;
  }

  static QueryPlanSummary toSummary(DQueryPlan p) {
    QueryPlanSummary dto = new QueryPlanSummary();
    dto.id = p.getId();
    dto.appMetricId = p.metric() == null ? 0L : p.metric().getId();
    dto.envName = p.env().getName();
    dto.hash = p.hash();
    dto.label = p.label();
    dto.queryTimeMicros = p.queryTimeMicros();
    dto.captureCount = p.captureCount();
    dto.whenCaptured = p.whenCaptured();
    return dto;
  }
}
