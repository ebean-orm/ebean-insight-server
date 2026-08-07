package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.HelloView;
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
    return new IndexView(Breadcrumb.EMPTY, apps);
  }

  private AppLink appLink(String appName) {
    final List<Env> envs = service.listAppEnvs(appName);
    if (envs.size() <= 1) {
      final String topUrl = envs.isEmpty()
        ? "/ux/top?app=" + urlEncode(appName) + "&range=4h"
        : topUrl(appName, envs.get(0).name());
      return new AppLink(appName, topUrl, List.of());
    }
    final List<EnvLink> links = envs.stream()
      .map(env -> new EnvLink(env.name(), topUrl(appName, env.name())))
      .toList();
    return new AppLink(appName, "", links);
  }

  @Get("hello")
  HelloView hello() {
    return new HelloView(Breadcrumb.EMPTY, "hello page");
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String topUrl(String appName, String envName) {
    return "/ux/top?app=" + urlEncode(appName) + "&env=" + urlEncode(envName) + "&range=4h";
  }
}
