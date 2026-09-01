package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.Produces;
import io.avaje.http.api.QueryParam;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.usage.UserUsageReportingService;
import org.ebean.monitor.v1.model.UserUsageSummary;
import org.ebean.monitor.v1.web.TimeWindow;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.UserUsageView;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Html
@Controller
@Path("/ux")
public final class UIUserUsageController {

  private final UserUsageReportingService service;
  private final Jsonb jsonb;

  public UIUserUsageController(UserUsageReportingService service) {
    this.service = service;
    this.jsonb = Jsonb.instance();
  }

  @Get("usage")
  UserUsageView usage(@QueryParam("sinceMinutes") @Nullable Long sinceMinutes,
                      @QueryParam("sinceHours") @Nullable Long sinceHours,
                      @QueryParam("user") @Nullable String user) {
    var window = TimeWindow.of(sinceMinutes, sinceHours, 60L);
    List<UserUsageSummary> users = service.summarize(
      sinceMinutes, sinceHours, user, 200);
    return new UserUsageView(
      new Breadcrumb(List.of(new Breadcrumb.Item("/ux", "Applications"),
        new Breadcrumb.Item("User usage"))),
      users, window.minutes(), !users.isEmpty());
  }

  @Get("usage/data")
  @Produces("application/json")
  String usageData(@QueryParam("sinceMinutes") @Nullable Long sinceMinutes,
                   @QueryParam("sinceHours") @Nullable Long sinceHours,
                   @QueryParam("user") @Nullable String user) {
    return jsonb.type(UserUsageSummary[].class)
      .toJson(service.summarize(sinceMinutes, sinceHours, user, 200).toArray(UserUsageSummary[]::new));
  }
}
