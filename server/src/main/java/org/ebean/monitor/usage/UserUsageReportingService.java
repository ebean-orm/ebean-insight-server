package org.ebean.monitor.usage;

import io.avaje.jex.http.BadRequestException;
import io.ebean.Database;
import org.ebean.monitor.Application;
import org.ebean.monitor.domain.DUserUsage;
import org.ebean.monitor.domain.query.QDUserUsage;
import org.ebean.monitor.v1.model.UserUsage;
import org.ebean.monitor.v1.model.UserUsageSummary;
import org.ebean.monitor.v1.web.TimeWindow;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Read-side queries for persisted user usage aggregates.
 */
public final class UserUsageReportingService {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final long DEFAULT_WINDOW_MINUTES = 60L;

  private final Database database;
  private final boolean forwardOnly;

  public UserUsageReportingService(Database database) {
    this.database = database;
    this.forwardOnly = Application.isForwardOnly();
  }

  public List<UserUsage> list(Long sinceMinutes, Long sinceHours, @Nullable String user,
                              @Nullable Integer limit) {
    if (forwardOnly) {
      return List.of();
    }
    TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_WINDOW_MINUTES);
    String userId = cleanUser(user);
    var query = new QDUserUsage(database);
    if (window.hasFrom()) {
      query.minuteAt.gt(window.from());
    }
    query.orderBy().minuteAt.asc().userId.asc().method.asc().path.asc();
    if (userId != null) {
      query.userId.eq(userId);
    }
    return query
      .setMaxRows(resolveLimit(limit))
      .findList()
      .stream()
      .map(UserUsageReportingService::toUsage)
      .toList();
  }

  public List<UserUsageSummary> summarize(Long sinceMinutes, Long sinceHours,
                                           @Nullable String user,
                                           @Nullable Integer limit) {
    if (forwardOnly) {
      return List.of();
    }
    TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_WINDOW_MINUTES);
    String userId = cleanUser(user);
    String timeClause = window.hasFrom() ? "where minute_at > :from" : "where 1 = 1";
    String userClause = userId == null ? "" : " and user_id = :userId";
    var query = database.sqlQuery("""
      select user_id,
             sum(request_count) as request_count,
             sum(total_micros) as total_micros,
             max(max_micros) as max_micros
        from ebean_insight.user_usage
       %s%s
       group by user_id
       order by total_micros desc, user_id
       limit :limit
      """.formatted(timeClause, userClause))
      .setParameter("limit", resolveLimit(limit));
    if (window.hasFrom()) {
      query.setParameter("from", window.from());
    }
    if (userId != null) {
      query.setParameter("userId", userId);
    }
    return query.mapTo((rs, _) -> {
      long count = rs.getLong("request_count");
      long totalMicros = rs.getLong("total_micros");
      return new UserUsageSummary(
        rs.getString("user_id"),
        count,
        totalMicros,
        count == 0 ? 0L : totalMicros / count,
        rs.getLong("max_micros"),
        window.minutes());
    }).findList();
  }

  private static UserUsage toUsage(DUserUsage usage) {
    return new UserUsage(
      usage.getMinuteAt(),
      usage.getUserId(),
      usage.getMethod(),
      usage.getPath(),
      usage.getRequestCount(),
      usage.getTotalMicros(),
      usage.getMaxMicros());
  }

  private static int resolveLimit(@Nullable Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (limit < 1) {
      throw new BadRequestException("limit must be at least 1");
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private static @Nullable String cleanUser(@Nullable String user) {
    if (user == null || user.isBlank()) {
      return null;
    }
    return user.trim();
  }
}
