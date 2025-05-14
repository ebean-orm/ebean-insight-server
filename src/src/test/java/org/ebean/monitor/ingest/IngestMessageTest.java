package org.ebean.monitor.ingest;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import io.ebean.FetchGroup;
import jakarta.inject.Inject;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.config.GlobalMetrics;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DGaugeEntry;
import org.ebean.monitor.domain.DTimedAgg;
import org.ebean.monitor.domain.DTimedEntry;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDGaugeEntry;
import org.ebean.monitor.domain.query.QDTimedAgg;
import org.ebean.monitor.domain.query.QDTimedEntry;
import org.ebean.monitor.rollup.Rollup;
import org.ebean.monitor.rollup.RollupD1;
import org.ebean.monitor.rollup.RollupM10;
import org.ebean.monitor.rollup.RollupM60;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ebean.monitor.ResourceHelp.metricRequest;
import static org.ebean.monitor.domain.query.QDAppMetric.Alias.name;
import static org.ebean.monitor.domain.query.QDGaugeEntry.Alias.value;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@InjectTest
class IngestMessageTest {

  private static final String testHashKey = "testHash";

  private final IngestMessage ingest;

  @Inject static Database database;

  public IngestMessageTest() {
    ProcessHeader header = new ProcessHeader();
    ProcessMetrics lookupMetrics = new ProcessMetrics();
    this.ingest = new IngestMessage(database, header, lookupMetrics);
  }

  private static MetricRequest req(String resourcePath) {
    final MetricRequest request = metricRequest(resourcePath);
    request.key = testHashKey;
    return request;
  }

  @BeforeAll
  public static void setup() {
    GlobalMetrics.init();
  }

  @Test
  public void ingest() {

    ingest.ingest(req("/request/req-1.json"));

    final DAppMetric met1 = new QDAppMetric()
      .name.eq("met1")
      .findOne();

    assertNotNull(met1);

    List<DTimedEntry> met1Entries = new QDTimedEntry()
      .metric.eq(met1)
      .findList();

    assertThat(met1Entries).hasSize(1);
    assertThat(met1Entries.get(0).getMean()).isEqualTo(42);

    ingest.ingest(req("/request/req-1b.json"));
    ingest.ingest(req("/request/req-1c.json"));

    met1Entries = new QDTimedEntry()
      .metric.eq(met1)
      .findList();

    assertThat(met1Entries).as("different loc in req-1b").hasSize(2);

    final List<DAppMetric> met1Hashes = new QDAppMetric()
      .name.eq("met1")
      .findList();

    assertThat(met1Hashes).hasSize(2);
  }

  @Test
  public void ingest2() {
    ingest.ingest(req("/request/req-2.json"));

    final List<DTimedEntry> entries = new QDTimedEntry()
      .app.name.eq("ord")
      .pod.name.eq("ord-5778623")
      .findList();

    assertThat(entries).hasSize(7);
  }

  @Test
  public void ingest_applicationMetrics() {

    ingest.ingest(req("/request/app-1.json"));

    final DApp app1 = new QDApp()
      .name.eq("app1")
      .findOne();

    assertThat(app1).isNotNull();

    final Map<String, DAppMetric> metrics =
      new QDAppMetric()
        .app.eq(app1)
        .name.asMapKey()
        .findMap();

    assertThat(metrics.get("some.thing")).isNotNull();
    assertThat(metrics.size()).isGreaterThan(0);

    final FetchGroup<DGaugeEntry> fetch =
      QDGaugeEntry
        .forFetchGroup()
        .select(value)
        .metric.fetch(name)
        .buildFetchGroup();

    final List<DGaugeEntry> entries = new QDGaugeEntry()
      .select(fetch)
      .app.eq(app1)
      .metric.name.startsWith("jvm.")
      .metric.name.asc()
      .findList();

    assertThat(entries).hasSize(9);
//    DbJson.of(entries)
//      .replace("id")
//      .assertContentMatches("/assertJson/app-1-gauge.json");
  }

  @Test
  public void ingest_full() {
    ingest.ingest(req("/request/full-1.json"));

    final List<DTimedEntry> timedEntries = new QDTimedEntry()
      .app.name.eq("full")
      .findList();

    assertThat(timedEntries).hasSize(6);

//    DbJson.of(timedEntries)
//      .replace("id", "eventTime")
//      .assertContentMatches("/assertJson/full-1-timed.json");


    final List<DGaugeEntry> gaugeEntries = new QDGaugeEntry()
      .app.name.eq("full")
      .findList();

    assertThat(gaugeEntries).hasSize(4);
//    DbJson.of(gaugeEntries)
//      .replace("id", "eventTime")
//      .assertContentMatches("/assertJson/full-1-gauge.json");
  }

  @Test
  public void ingest_full2_aggregation() {

    ingest.ingest(req("/request/full-2a.json"));
    ingest.ingest(req("/request/full-2b.json"));

    QDTimedAgg a = QDTimedAgg.alias();

    final List<DTimedAgg> timedEntries = new QDTimedAgg()
      .select(a.app, a.metric, a.env, a.db, a.eventTime, a.count, a.total, a.max)
      .app.name.eq("full2")
      .findList();

    assertThat(timedEntries).hasSize(6);

    final Instant asOf = Instant.parse("2019-11-15T10:29:00Z");
    Rollup rollup = new Rollup(database, asOf);
    rollup.rollup();
    new RollupM10(asOf).run();
    new RollupM60(asOf).run();
    new RollupD1(asOf).run();
  }

  @Test
  public void ingest_full3_aggregation() {

    ingest.ingest(req("/request/full-3.json"));

    QDTimedAgg a = QDTimedAgg.alias();
    final List<DTimedAgg> timedEntries = new QDTimedAgg()
      .select(a.app, a.metric, a.env, a.db, a.eventTime, a.count, a.total, a.max)
      .app.name.eq("full3")
      .findList();

    assertThat(timedEntries).hasSize(30);
  }

}
