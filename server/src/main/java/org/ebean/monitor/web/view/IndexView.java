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
                        String datasourcePoolConfigUrl, String webApiConfigUrl, String jvmConfigUrl,
                        String dmlConfigUrl, boolean datasourcePoolEnabled, boolean webApiEnabled,
                        boolean jvmEnabled, boolean dmlEnabled) {
  }

  public record EnvLink(String name, String topUrl) {
  }
}
