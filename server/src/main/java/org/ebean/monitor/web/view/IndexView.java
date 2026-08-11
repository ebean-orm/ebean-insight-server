package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

import java.util.List;

@JStache(path = "index")
public record IndexView(
  Breadcrumb breadcrumb,
  List<AppLink> apps,
  boolean hasApps
) {

  /** One application and its environment-specific Top dashboard links. */
  public record AppLink(String name, String topUrl, List<EnvLink> envs,
                        String datasourcePoolConfigUrl, String webApiConfigUrl,
                        boolean datasourcePoolEnabled, boolean webApiEnabled) {
  }

  public record EnvLink(String name, String topUrl) {
  }
}
