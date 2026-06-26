package org.ebean.monitor.domain;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import io.ebean.SqlRow;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code @DbJsonB Map<String,String> tags} mapping round-trips through
 * the Postgres {@code jsonb} column and that {@code tags ->> 'key'} SQL still resolves
 * individual tag values (the reason tags are stored as jsonb rather than a plain blob).
 */
@InjectTest
class DAppMetricTagsTest {

  @Inject static Database database;

  private DApp app(String name) {
    DApp existing = new QDApp().name.eq(name).findOne();
    if (existing != null) {
      return existing;
    }
    DApp app = new DApp(name);
    database.save(app);
    return app;
  }

  @Test
  void mapTags_roundTripAndJsonbExpression() {
    String appName = "tags-rt-" + System.nanoTime();
    String key = "tags-k-" + System.nanoTime();
    DApp app = app(appName);

    Map<String, String> tags = Map.of("kind", "orm", "type", "Customer", "label", "Customer.findList");
    database.save(new DAppMetric(app, key, "ebean.query", tags, true));

    // reload the entity and confirm the Map deserialises from jsonb
    DAppMetric reloaded = new QDAppMetric().key.eq(key).findOne();
    assertThat(reloaded).isNotNull();
    assertThat(reloaded.getTags())
      .containsEntry("kind", "orm")
      .containsEntry("type", "Customer")
      .containsEntry("label", "Customer.findList");

    // confirm the jsonb '->>' expression resolves a tag value for the saved row
    SqlRow row = database.sqlQuery(
        "select tags ->> 'label' as label from ebean_insight.app_metric where key = ?")
      .setParameter(key)
      .findOne();
    assertThat(row).isNotNull();
    assertThat(row.getString("label")).isEqualTo("Customer.findList");
  }

  @Test
  void nullTags_supported() {
    String appName = "tags-null-" + System.nanoTime();
    String key = "tags-nk-" + System.nanoTime();
    DApp app = app(appName);

    database.save(new DAppMetric(app, key, "txn.main", null, false));

    DAppMetric reloaded = new QDAppMetric().key.eq(key).findOne();
    assertThat(reloaded).isNotNull();
    assertThat(reloaded.getTags()).isNull();
  }
}
