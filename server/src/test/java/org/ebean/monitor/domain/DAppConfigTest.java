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
  void dashboardFlags_defaultToDisabled() {
    var app = new DApp("config-test");

    assertThat(app.isDatasourcePoolDashboardEnabled()).isFalse();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
  }

  @Test
  void dashboardFlags_areStoredInGenericConfig() {
    var app = new DApp("config-test");

    app.setDatasourcePoolDashboardEnabled(true);
    app.setWebApiDashboardEnabled(false);

    assertThat(app.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
    assertThat(app.getConfig())
      .containsEntry("datasourcePool", "true")
      .containsEntry("webApi", "false");
  }

  @Test
  void dashboardConfig_canBeLoadedFromJsonbShape() {
    var app = new DApp("config-test");
    app.setConfig(Map.of("datasourcePool", "true"));

    assertThat(app.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(app.isWebApiDashboardEnabled()).isFalse();
  }

  @Test
  void dashboardConfig_roundTripsThroughJsonb() {
    var name = "config-rt-" + System.nanoTime();
    var app = new DApp(name);
    app.setDatasourcePoolDashboardEnabled(true);
    database.save(app);

    var reloaded = new QDApp().name.eq(name).findOne();

    assertThat(reloaded).isNotNull();
    assertThat(reloaded.isDatasourcePoolDashboardEnabled()).isTrue();
    assertThat(reloaded.isWebApiDashboardEnabled()).isFalse();
  }
}
