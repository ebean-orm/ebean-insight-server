package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import io.ebean.annotation.DbJsonB;
import io.ebean.annotation.MutationDetection;
import org.ebean.monitor.domain.finder.DAppFinder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "Application" metrics relate to.
 */
@Cache(nearCache = true, naturalKey = {"org", "name"})
@Entity
@Table(name = "ebean_insight.app")
public class DApp extends BaseDomain {

  public static final DAppFinder find = new DAppFinder();
  private static final String DATASOURCE_POOL_DASHBOARD = "datasourcePool";
  private static final String WEB_API_DASHBOARD = "webApi";
  private static final String JVM_DASHBOARD = "jvm";

  @Column(nullable = false, length = 200)
  private String name;

  /**
   * Optional per-application dashboard flags stored as JSONB. Missing flags
   * default to disabled so existing applications retain the current UX.
   */
  @DbJsonB(mutationDetection = MutationDetection.NONE)
  private Map<String, String> config;

  public DApp(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isDatasourcePoolDashboardEnabled() {
    return isDashboardEnabled(DATASOURCE_POOL_DASHBOARD);
  }

  public boolean isWebApiDashboardEnabled() {
    return isDashboardEnabled(WEB_API_DASHBOARD);
  }

  public boolean isJvmDashboardEnabled() {
    return isDashboardEnabled(JVM_DASHBOARD);
  }

  public void setDatasourcePoolDashboardEnabled(boolean enabled) {
    setDashboardEnabled(DATASOURCE_POOL_DASHBOARD, enabled);
  }

  public void setWebApiDashboardEnabled(boolean enabled) {
    setDashboardEnabled(WEB_API_DASHBOARD, enabled);
  }

  public void setJvmDashboardEnabled(boolean enabled) {
    setDashboardEnabled(JVM_DASHBOARD, enabled);
  }

  public Map<String, String> getConfig() {
    return config == null ? Map.of() : Map.copyOf(config);
  }

  public void setConfig(Map<String, String> config) {
    this.config = config == null ? null : Map.copyOf(config);
  }

  private boolean isDashboardEnabled(String dashboard) {
    return config != null && Boolean.parseBoolean(config.get(dashboard));
  }

  private void setDashboardEnabled(String dashboard, boolean enabled) {
    var updated = new LinkedHashMap<String, String>();
    if (config != null) {
      updated.putAll(config);
    }
    updated.put(dashboard, Boolean.toString(enabled));
    config = updated;
  }
}
