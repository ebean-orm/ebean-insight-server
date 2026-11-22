package org.ebean.monitor.domain;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.query.QDApp;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class DAppConfigTest {

  @Inject static Database database;

  @Test
  void dashboardFlags_useDefaults() {
    var app = new DApp("config-test");

    assertThat(app.isQueryDashboardEnabled()).isTrue();
    assertThat(app.isDatasourcePoolDashboardEnabled()).isFalse();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
    assertThat(app.isJvmDashboardEnabled()).isFalse();
    assertThat(app.isDmlDashboardEnabled()).isFalse();
    assertThat(app.isAppComponentDashboardEnabled()).isFalse();
  }

  @Test
  void dashboardFlags_areStoredInGenericConfig() {
    var app = new DApp("config-test");

    app.setQueryDashboardEnabled(false);
    app.setDatasourcePoolDashboardEnabled(true);
    app.setWebApiDashboardEnabled(false);
    app.setJvmDashboardEnabled(true);
    app.setDmlDashboardEnabled(true);
    app.setAppComponentDashboardEnabled(true);

    assertThat(app.isQueryDashboardEnabled()).isFalse();
    assertThat(app.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
    assertThat(app.getConfig())
      .containsEntry("query", "false")
      .containsEntry("datasourcePool", "true")
      .containsEntry("webApi", "false")
      .containsEntry("jvm", "true")
      .containsEntry("dml", "true")
      .containsEntry("appComponent", "true");
  }

  @Test
  void dashboardConfig_canBeLoadedFromJsonbShape() {
    var app = new DApp("config-test");
    app.setConfig(Map.of("datasourcePool", "true"));

    assertThat(app.isQueryDashboardEnabled()).isTrue();
    assertThat(app.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
    assertThat(app.isJvmDashboardEnabled()).isFalse();
    assertThat(app.isDmlDashboardEnabled()).isFalse();
    assertThat(app.isAppComponentDashboardEnabled()).isFalse();
  }

  @Test
  void dashboardConfig_roundTripsThroughJsonb() {
    var name = "config-rt-" + System.nanoTime();
    var app = new DApp(name);
    app.setQueryDashboardEnabled(false);
    app.setDatasourcePoolDashboardEnabled(true);
    app.setJvmDashboardEnabled(true);
    app.setDmlDashboardEnabled(true);
    app.setAppComponentDashboardEnabled(true);
    database.save(app);

    var reloaded = new QDApp().name.eq(name).findOne();

    assertThat(reloaded).isNotNull();
    assertThat(reloaded.isQueryDashboardEnabled()).isFalse();
    assertThat(reloaded.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(reloaded.isWebApiDashboardEnabled()).isFalse();
    assertThat(reloaded.isJvmDashboardEnabled()).isTrue();
    assertThat(reloaded.isDmlDashboardEnabled()).isTrue();
    assertThat(reloaded.isAppComponentDashboardEnabled()).isTrue();
  }
}
