package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.IndexView;
import org.ebean.monitor.web.view.IndexView.AppLink;
import org.ebean.monitor.web.view.IndexView.EnvLink;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Html
@Controller
@Path("/ux")
public class UIIndexController {

  private final V1QueryService service;

  public UIIndexController(V1QueryService service) {
    this.service = service;
  }

  @Get
  IndexView home() {
    final List<AppLink> apps = service.listApps(null, null).stream()
      .map(app -> appLink(app.name()))
      .toList();
    return new IndexView(Breadcrumb.EMPTY, apps, !apps.isEmpty());
  }

  @Get("app-config")
  IndexView configure(@QueryParam("app") String appName,
                      @QueryParam("datasourcePool") boolean datasourcePool,
                      @QueryParam("webApi") boolean webApi,
                      @QueryParam("jvm") boolean jvm,
                      @QueryParam("dml") boolean dml) {
    service.setDashboardConfig(appName, datasourcePool, webApi, jvm, dml);
    return home();
  }

  private AppLink appLink(String appName) {
    final List<Env> envs = service.listAppEnvs(appName);
    if (envs.size() <= 1) {
      final String topUrl = envs.isEmpty()
        ? "/ux/top?app=" + urlEncode(appName) + "&range=4h"
        : topUrl(appName, envs.get(0).name());
      return appLink(appName, topUrl, List.of());
    }
    final List<EnvLink> links = envs.stream()
      .map(env -> new EnvLink(env.name(), topUrl(appName, env.name())))
      .toList();
    return appLink(appName, "", links);
  }

  private AppLink appLink(String appName, String topUrl, List<EnvLink> envs) {
    final boolean datasourcePool = service.isDatasourcePoolDashboardEnabled(appName);
    final boolean webApi = service.isWebApiDashboardEnabled(appName);
    final boolean jvm = service.isJvmDashboardEnabled(appName);
    final boolean dml = service.isDmlDashboardEnabled(appName);
    final String datasourcePoolConfigUrl = "/ux/app-config?app=" + urlEncode(appName)
      + "&datasourcePool=" + !datasourcePool + "&webApi=" + webApi + "&jvm=" + jvm
      + "&dml=" + dml;
    final String webApiConfigUrl = "/ux/app-config?app=" + urlEncode(appName)
      + "&datasourcePool=" + datasourcePool + "&webApi=" + !webApi + "&jvm=" + jvm
      + "&dml=" + dml;
    final String jvmConfigUrl = "/ux/app-config?app=" + urlEncode(appName)
      + "&datasourcePool=" + datasourcePool + "&webApi=" + webApi + "&jvm=" + !jvm
      + "&dml=" + dml;
    final String dmlConfigUrl = "/ux/app-config?app=" + urlEncode(appName)
      + "&datasourcePool=" + datasourcePool + "&webApi=" + webApi + "&jvm=" + jvm
      + "&dml=" + !dml;
    return new AppLink(appName, topUrl, envs, datasourcePoolConfigUrl, webApiConfigUrl, jvmConfigUrl,
      dmlConfigUrl, datasourcePool, webApi, jvm, dml);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String topUrl(String appName, String envName) {
    return "/ux/top?app=" + urlEncode(appName) + "&env=" + urlEncode(envName) + "&range=4h";
  }
}
