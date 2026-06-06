package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppSummary;

@Controller
public final class V1AppsController implements AppsApi {

  private final V1QueryService service;

  public V1AppsController(V1QueryService service) {
    this.service = service;
  }

  @Override
  public List<App> listApps(Long activeWithinMinutes, Long activeWithinHours) {
    return service.listApps(activeWithinMinutes, activeWithinHours);
  }

  @Override
  public AppSummary getApp(String app) {
    return service.getApp(app);
  }
}
