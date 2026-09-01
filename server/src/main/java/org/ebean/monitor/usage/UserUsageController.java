package org.ebean.monitor.usage;

import io.avaje.http.api.Controller;
import org.ebean.monitor.v1.UsageApi;
import org.ebean.monitor.v1.model.UserUsage;
import org.ebean.monitor.v1.model.UserUsageSummary;

import java.util.List;

@Controller
public final class UserUsageController implements UsageApi {

  private final UserUsageReportingService service;

  public UserUsageController(UserUsageReportingService service) {
    this.service = service;
  }

  @Override
  public List<UserUsage> listUserUsage(Long sinceMinutes, Long sinceHours, String user,
                                       Integer limit) {
    return service.list(sinceMinutes, sinceHours, user, limit);
  }

  @Override
  public List<UserUsageSummary> summarizeUserUsage(Long sinceMinutes, Long sinceHours,
                                                   String user, Integer limit) {
    return service.summarize(sinceMinutes, sinceHours, user, limit);
  }
}
