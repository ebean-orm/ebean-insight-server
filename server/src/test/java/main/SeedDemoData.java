package main;

import io.ebean.Database;
import io.ebean.Transaction;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppDatabase;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DAppPod;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppDatabase;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDEnv;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Dev-only tool that seeds realistic-looking historical query metrics directly
 * into the {@code timed_m1}/{@code timed_m10}/{@code timed_m60} rollup tables
 * so the {@code /ux/top} dashboard has enough data to look right
 * across every time-range option (30m/1h/6h/24h/2d/7d), plus a handful of
 * captured {@code query_plan} rows so the {@code /ux/metric-detail}
 * drill-down page (hash breakdown + recent plans) has something to show.
 * <p>
 * Bypasses the real ingest + {@code Rollup} pipeline entirely for speed -
 * synthetic pre-aggregated rows are inserted directly into each tier,
 * following a daily business-hours "wave" (plus weekend dip, per-bucket
 * jitter, and one spiky batch-job label) across a fixed set of query labels
 * and timer labels so the top-15 + "Other" stacked bars look realistic at every
 * range. Several query labels are split across two underlying query hashes
 * (e.g. an indexed fast path and a slower sequential-scan path) so the
 * metric-detail drill-down's "breakdown by hash" table has more than one row
 * to look right.
 * <p>
 * Run against the local docker Postgres started by {@link StartPostgresDocker}
 * (see {@code src/test/main/StartPostgresDocker.main()}). Re-running is safe -
 * previously seeded rows for the target app are deleted first.
 */
public class SeedDemoData {

  private static final String APP_NAME = "shop-app";
  private static final String ENV_NAME = "prod";
  private static final String METRIC_NAME = "ebean.query";
  private static final String DB_NAME = "db";

  /**
   * One underlying query hash sharing a {@link LabelSpec}'s label. {@code share}
   * is this hash's fraction of the label's total call volume; {@code meanMultiplier}
   * scales the label's baseline mean latency (e.g. {@code 0.8} for a fast indexed
   * path, {@code 2.2} for a slow sequential-scan path). {@code planShapeHash}/
   * {@code plan} describe the (most recent) captured query plan for this hash;
   * when {@code regressedPlanShapeHash}/{@code regressedPlan} are non-null, an
   * older capture using the original shape is seeded first followed by a more
   * recent capture using the regressed shape, so the drill-down page's "shape
   * changed" flag has a real example to show.
   */
  private record HashSpec(String suffix, String loc, double share, double meanMultiplier,
                          String sql, String planShapeHash, String plan,
                          String regressedPlanShapeHash, String regressedPlan) {

    private HashSpec(String suffix, String loc, double share, double meanMultiplier,
                     String sql, String planShapeHash, String plan) {
      this(suffix, loc, share, meanMultiplier, sql, planShapeHash, plan, null, null);
    }
  }

  /**
   * A synthetic query label. {@code weight} is roughly "calls per minute at
   * peak"; {@code batchHourUtc} marks a rare, spiky once-a-day batch job
   * (mostly zero, big burst in that UTC hour) instead of the normal
   * business-hours wave. {@code hashes} splits the label's volume across one
   * or more underlying query hashes.
   */
  private record LabelSpec(String label, String type, double weight, long meanMicros,
                           Integer batchHourUtc, List<HashSpec> hashes) {
  }

  /** A synthetic DML timer label, without query-hash or plan metadata. */
  private record DmlSpec(String label, double weight, long meanMicros, Integer batchHourUtc) {
  }

  /** A synthetic application-component timer label. */
  private record AppComponentSpec(String label, double weight, long meanMicros, Integer batchHourUtc) {
  }

  private static final List<LabelSpec> LABELS = List.of(
    new LabelSpec("Customer.findList", "Customer", 6.0, 15_000L, null, List.of(
      new HashSpec("idx", "CustomerRepository.java:41", 0.7, 0.85,
        "select id, name, email, when_created from customer where status = ? order by name limit ?",
        "cust-idx-v1",
        "Index Scan using customer_status_idx on customer  (cost=0.29..8.32 rows=50 width=96)\n  Index Cond: (status = $1)"),
      new HashSpec("noidx", "CustomerRepository.java:77", 0.3, 2.4,
        "select id, name, email, when_created from customer where lower(email) like ? order by name limit ?",
        "cust-seq-v1",
        "Seq Scan on customer  (cost=0.00..18320.00 rows=120 width=96)\n  Filter: (lower(email) ~~ $1)")
    )),
    new LabelSpec("Order.findList", "Order", 5.0, 22_000L, null, List.of(
      new HashSpec("idx", "OrderRepository.java:55", 0.65, 0.9,
        "select o.id, o.status, o.total, o.when_created from \"order\" o where o.customer_id = ? order by o.when_created desc limit ?",
        "order-idx-v1",
        "Index Scan using order_customer_idx on \"order\" o  (cost=0.42..6.51 rows=8 width=48)\n  Index Cond: (customer_id = $1)"),
      new HashSpec("join", "OrderRepository.java:102", 0.35, 1.9,
        "select o.id, o.status, o.total from \"order\" o join order_line ol on ol.order_id = o.id where ol.product_id = ? group by o.id",
        "order-join-nl-v1",
        "Nested Loop  (cost=0.85..245.10 rows=42 width=48)\n  ->  Index Scan using order_line_product_idx on order_line ol\n  ->  Index Scan using order_pk on \"order\" o",
        "order-join-hj-v2",
        "Hash Join  (cost=812.00..4520.55 rows=4200 width=48)\n  Hash Cond: (o.id = ol.order_id)\n  ->  Seq Scan on \"order\" o\n  ->  Hash\n        ->  Seq Scan on order_line ol")
    )),
    new LabelSpec("OrderLine.findList", "OrderLine", 4.0, 9_000L, null, List.of(
      new HashSpec("default", "OrderLineRepository.java:29", 1.0, 1.0,
        "select id, order_id, product_id, quantity from order_line where order_id = ?",
        "orderline-idx-v1",
        "Index Scan using order_line_order_idx on order_line  (cost=0.29..4.31 rows=6 width=32)\n  Index Cond: (order_id = $1)")
    )),
    new LabelSpec("Product.search", "Product", 3.5, 60_000L, null, List.of(
      new HashSpec("idx", "ProductSearchDao.java:38", 0.6, 0.75,
        "select id, name, sku, price from product where sku = ?",
        "product-idx-v1",
        "Index Scan using product_sku_idx on product  (cost=0.42..8.44 rows=1 width=120)\n  Index Cond: (sku = $1)"),
      new HashSpec("fulltext", "ProductSearchDao.java:91", 0.4, 1.7,
        "select id, name, sku, price from product where to_tsvector('english', name) @@ plainto_tsquery(?)",
        "product-fts-v1",
        "Seq Scan on product  (cost=0.00..12500.00 rows=500 width=120)\n  Filter: (to_tsvector('english'::regconfig, name) @@ '''widget'''::tsquery)")
    )),
    new LabelSpec("User.findById", "User", 8.0, 4_000L, null, List.of(
      new HashSpec("default", "UserRepository.java:22", 1.0, 1.0,
        "select id, username, email from app_user where id = ?",
        "user-idx-v1",
        "Index Scan using app_user_pk on app_user  (cost=0.29..2.31 rows=1 width=64)\n  Index Cond: (id = $1)")
    )),
    new LabelSpec("Invoice.findList", "Invoice", 2.0, 30_000L, null, List.of(
      new HashSpec("idx", "InvoiceRepository.java:66", 0.8, 0.85,
        "select id, customer_id, total, status from invoice where customer_id = ? order by when_created desc",
        "invoice-idx-v1",
        "Index Scan using invoice_customer_idx on invoice  (cost=0.42..9.87 rows=12 width=56)\n  Index Cond: (customer_id = $1)"),
      new HashSpec("noidx", "InvoiceRepository.java:120", 0.2, 2.1,
        "select id, customer_id, total, status from invoice where status = ? and total > ?",
        "invoice-seq-v1",
        "Seq Scan on invoice  (cost=0.00..9450.00 rows=340 width=56)\n  Filter: ((status = $1) AND (total > $2))")
    )),
    new LabelSpec("Payment.process", "Payment", 1.5, 45_000L, null, List.of(
      new HashSpec("default", "PaymentService.java:88", 1.0, 1.0,
        "insert into payment (order_id, amount, method, status) values (?, ?, ?, ?)",
        "payment-insert-v1",
        "Insert on payment  (cost=0.00..0.01 rows=0 width=0)")
    )),
    new LabelSpec("Address.findList", "Address", 1.0, 8_000L, null, List.of(
      new HashSpec("default", "AddressRepository.java:19", 1.0, 1.0,
        "select id, line1, city, postcode from address where customer_id = ?",
        "address-idx-v1",
        "Index Scan using address_customer_idx on address  (cost=0.29..3.31 rows=2 width=64)\n  Index Cond: (customer_id = $1)")
    )),
    new LabelSpec("Report.generate", "Report", 0.3, 250_000L, 3, List.of(
      new HashSpec("default", "ReportService.java:140", 1.0, 1.0,
        "select date_trunc('day', when_created) as day, count(*), sum(total) from \"order\" group by 1 order by 1",
        "report-agg-v1",
        "HashAggregate  (cost=15200.00..15450.00 rows=365 width=48)\n  Group Key: date_trunc('day', when_created)\n  ->  Seq Scan on \"order\"  (cost=0.00..12500.00 rows=180000 width=16)")
    )),
    // A "query family" demo: a dot-hierarchy chain of one root finder query
    // plus three lazy-loaded fetch-path descendants (Ebean names each
    // subsequent lazy load by appending its property path to the root
    // label), so the metric-detail page's "query family" tree has a
    // realistic multi-level example to show - including a deliberately slow
    // leaf query worth spotting.
    new LabelSpec("CMachine.findByGid", "CMachine", 4.5, 9_000L, null, List.of(
      new HashSpec("idx", "nz.co.eroad.central.access.repository.MachineRepository.findMachineByGid", 1.0, 1.0,
        "select id, gid, name, status from c_machine where gid = ?",
        "cmachine-idx-v1",
        "Index Scan using c_machine_gid_idx on c_machine  (cost=0.29..8.31 rows=1 width=96)\n  Index Cond: (gid = $1)")
    )),
    new LabelSpec("CMachine.findByGid.organisationMachines", "CMachine", 3.2, 14_000L, null, List.of(
      new HashSpec("default", "nz.co.eroad.central.access.repository.OrganisationMachineRepository.findByMachineId", 1.0, 1.0,
        "select id, machine_id, organisation_id from organisation_machine where machine_id = ?",
        "org-machine-idx-v1",
        "Index Scan using organisation_machine_machine_idx on organisation_machine  (cost=0.29..6.31 rows=4 width=48)\n  Index Cond: (machine_id = $1)")
    )),
    new LabelSpec("CMachine.findByGid.organisationMachines.thirdPartyIdentifiers", "CMachine", 2.0, 6_000L, null, List.of(
      new HashSpec("default", "nz.co.eroad.central.access.repository.OrganisationMachineRepository.findThirdPartyIdentifiers", 1.0, 1.0,
        "select id, org_machine_id, provider, external_id from third_party_identifier where org_machine_id = ?",
        "third-party-idx-v1",
        "Index Scan using third_party_identifier_org_machine_idx on third_party_identifier  (cost=0.29..4.31 rows=2 width=64)\n  Index Cond: (org_machine_id = $1)")
    )),
    new LabelSpec("CMachine.findByGid.organisationMachines.thirdPartyIdentifiers.query", "CMachine", 4.0, 40_000L, null, List.of(
      new HashSpec("default", "nz.co.eroad.central.access.repository.ThirdPartyIdentifierRepository.findByIdWithProvider", 1.0, 1.0,
        "select t.id, t.external_id, p.name, p.status from third_party_identifier t join provider p on p.id = t.provider_id where t.id = ?",
        "third-party-nl-v1",
        "Nested Loop  (cost=0.42..612.30 rows=1 width=80)\n  ->  Index Scan using third_party_identifier_pk on third_party_identifier t\n  ->  Index Scan using provider_pk on provider p")
    )),
    new LabelSpec("Category.findList", "Category", 2.8, 11_000L, null, List.of(
      new HashSpec("default", "CategoryRepository.java:34", 1.0, 1.0,
        "select id, name, parent_id from category where active = true order by name",
        "category-active-v1",
        "Index Scan using category_active_idx on category  (cost=0.29..18.42 rows=240 width=72)\n  Index Cond: (active = true)")
    )),
    new LabelSpec("Shipment.findList", "Shipment", 2.4, 28_000L, null, List.of(
      new HashSpec("default", "ShipmentRepository.java:48", 1.0, 1.0,
        "select id, order_id, carrier, tracking_number from shipment where order_id = ?",
        "shipment-order-v1",
        "Index Scan using shipment_order_idx on shipment  (cost=0.29..7.42 rows=3 width=88)\n  Index Cond: (order_id = $1)")
    )),
    new LabelSpec("Review.findList", "Review", 2.1, 18_000L, null, List.of(
      new HashSpec("default", "ReviewRepository.java:57", 1.0, 1.0,
        "select id, product_id, rating, comment from review where product_id = ? order by when_created desc",
        "review-product-v1",
        "Index Scan using review_product_created_idx on review  (cost=0.42..14.20 rows=18 width=112)\n  Index Cond: (product_id = $1)")
    )),
    new LabelSpec("Warehouse.findList", "Warehouse", 1.8, 24_000L, null, List.of(
      new HashSpec("default", "WarehouseRepository.java:29", 1.0, 1.0,
        "select id, code, name, region from warehouse where region = ?",
        "warehouse-region-v1",
        "Index Scan using warehouse_region_idx on warehouse  (cost=0.29..9.11 rows=12 width=80)\n  Index Cond: (region = $1)")
    )),
    new LabelSpec("Promotion.findList", "Promotion", 1.4, 35_000L, null, List.of(
      new HashSpec("default", "PromotionRepository.java:71", 1.0, 1.0,
        "select id, code, discount from promotion where starts_at <= ? and ends_at > ?",
        "promotion-active-v1",
        "Index Scan using promotion_active_dates_idx on promotion  (cost=0.42..22.55 rows=9 width=64)\n  Index Cond: ((starts_at <= $1) AND (ends_at > $2))")
    )),
    new LabelSpec("Session.findById", "Session", 1.2, 7_000L, null, List.of(
      new HashSpec("default", "SessionRepository.java:18", 1.0, 1.0,
        "select id, user_id, expires_at from user_session where id = ?",
        "session-pk-v1",
        "Index Scan using user_session_pk on user_session  (cost=0.29..2.31 rows=1 width=48)\n  Index Cond: (id = $1)")
    )),
    new LabelSpec("Notification.send", "Notification", 0.8, 42_000L, null, List.of(
      new HashSpec("default", "NotificationService.java:103", 1.0, 1.0,
        "insert into notification (user_id, channel, payload, sent_at) values (?, ?, ?, ?)",
        "notification-insert-v1",
        "Insert on notification  (cost=0.00..0.02 rows=0 width=0)")
    ))
  );

  private static final List<DmlSpec> DML_LABELS = List.of(
    new DmlSpec("Customer.insert", 3.0, 2_500L, null),
    new DmlSpec("Order.insert", 2.0, 6_500L, null),
    new DmlSpec("Order.update", 8.0, 4_000L, null),
    new DmlSpec("Order.delete", 1.2, 3_000L, null),
    new DmlSpec("OrderLine.insertBatch", 4.0, 1_800L, null),
    new DmlSpec("CommonOrderAudit.insert", 2.5, 7_000L, 14)
  );

  private static final List<AppComponentSpec> APP_COMPONENT_LABELS = List.of(
    new AppComponentSpec("OrderService.placeOrder", 2.8, 48_000L, null),
    new AppComponentSpec("CustomerCache.refresh", 1.6, 12_000L, null),
    new AppComponentSpec("BillingClient.charge", 2.2, 72_000L, null),
    new AppComponentSpec("ReportExport.run", 0.5, 125_000L, 3)
  );

  public static void main(String[] args) {
    Database database = Database.builder().name(DB_NAME).loadFromProperties().defaultDatabase(true).build();
    try {
      new SeedDemoData(database).run();
    } finally {
      database.shutdown(true, false);
    }
  }

  private final Database db;
  private final Random random = new Random(42);

  SeedDemoData(Database db) {
    this.db = db;
  }

  /** One seeded metric row for a specific {@link LabelSpec}/{@link HashSpec} pair. */
  private record HashMetric(HashSpec spec, DAppMetric metric) {
  }

  void run() {
    extendPartitions();
    DApp app = findOrCreateApp();
    app.setDatasourcePoolDashboardEnabled(true);
    app.setWebApiDashboardEnabled(true);
    app.setJvmDashboardEnabled(true);
    app.setDmlDashboardEnabled(true);
    app.setAppComponentDashboardEnabled(true);
    db.save(app);
    DEnv env = findOrCreateEnv();
    DAppDatabase appDb = findOrCreateAppDb(app);
    Map<String, List<HashMetric>> metricsByLabel = findOrCreateMetrics(app);
    Map<String, DAppMetric> dmlMetrics = findOrCreateDmlMetrics(app);
    Map<String, DAppMetric> poolMetrics = findOrCreatePoolMetrics(app);
    Map<String, DAppMetric> poolTimingMetrics = findOrCreatePoolTimingMetrics(app);
    Map<String, DAppMetric> webApiMetrics = findOrCreateWebApiMetrics(app);
    Map<String, DAppMetric> appComponentMetrics = findOrCreateAppComponentMetrics(app);
    Map<String, DAppMetric> jvmMetrics = findOrCreateJvmMetrics(app);
    List<DAppPod> jvmPods = findOrCreateJvmPods(app);

    Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    deleteExisting(app);

    int m1 = seedTier("timed_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now, 1, env, appDb, metricsByLabel);
    int m10 = seedTier("timed_m10", roundDown(now.minus(3, ChronoUnit.DAYS), 10), now, 10, env, appDb, metricsByLabel);
    int m60 = seedTier("timed_m60", roundDown(now.minus(9, ChronoUnit.DAYS), 60), now, 60, env, appDb, metricsByLabel);
    seedPoolTiming("timed_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now, 1,
      env, app, poolTimingMetrics);
    seedPoolTiming("timed_m10", roundDown(now.minus(2, ChronoUnit.DAYS), 10), now, 10,
      env, app, poolTimingMetrics);
    seedPoolTiming("timed_m60", roundDown(now.minus(7, ChronoUnit.DAYS), 60), now, 60,
      env, app, poolTimingMetrics);
    seedWebApi("timed_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now, 1,
      env, app, webApiMetrics);
    seedWebApi("timed_m10", roundDown(now.minus(2, ChronoUnit.DAYS), 10), now, 10,
      env, app, webApiMetrics);
    seedWebApi("timed_m60", roundDown(now.minus(7, ChronoUnit.DAYS), 60), now, 60,
      env, app, webApiMetrics);
    int dmlM1 = seedDml("timed_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now, 1,
      env, app, dmlMetrics);
    int dmlM10 = seedDml("timed_m10", roundDown(now.minus(2, ChronoUnit.DAYS), 10), now, 10,
      env, app, dmlMetrics);
    int dmlM60 = seedDml("timed_m60", roundDown(now.minus(7, ChronoUnit.DAYS), 60), now, 60,
      env, app, dmlMetrics);
    int appComponentM1 = seedAppComponents("timed_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now, 1,
      env, app, appComponentMetrics);
    int appComponentM10 = seedAppComponents("timed_m10", roundDown(now.minus(2, ChronoUnit.DAYS), 10), now, 10,
      env, app, appComponentMetrics);
    int appComponentM60 = seedAppComponents("timed_m60", roundDown(now.minus(7, ChronoUnit.DAYS), 60), now, 60,
      env, app, appComponentMetrics);
    int poolM1 = seedPoolGauge("gauge_m1", roundDown(now.minus(4, ChronoUnit.HOURS), 1), now,
      1, env, app, poolMetrics);
    int poolM10 = seedPoolGauge("gauge_m10", roundDown(now.minus(2, ChronoUnit.DAYS), 10), now,
      10, env, app, poolMetrics);
    int poolM60 = seedPoolGauge("gauge_m60", roundDown(now.minus(7, ChronoUnit.DAYS), 60), now,
      60, env, app, poolMetrics);
    int jvm = seedJvmGauge("gauge_entry", roundDown(now.minus(7, ChronoUnit.DAYS), 1), now,
      1, env, app, jvmMetrics, jvmPods);

    int plans = seedQueryPlans(app, env, metricsByLabel);

    System.out.println("Seeded app=" + APP_NAME + " env=" + ENV_NAME
      + " -> timed_m1:" + m1 + " timed_m10:" + m10 + " timed_m60:" + m60
      + " gauge_m1:" + poolM1 + " gauge_m10:" + poolM10 + " gauge_m60:" + poolM60
      + " dml_m1:" + dmlM1 + " dml_m10:" + dmlM10 + " dml_m60:" + dmlM60
      + " app_component_m1:" + appComponentM1 + " app_component_m10:" + appComponentM10
      + " app_component_m60:" + appComponentM60
      + " jvm:" + jvm
      + " query_plan:" + plans + " rows");
    System.out.println("View at http://localhost:8091/ux/top?app=" + APP_NAME + "&range=30m");
  }

  /**
   * Extend day partitions back far enough to cover the seeded history (and
   * forward, matching the normal daily maintenance job) for each rollup
   * table, using the same {@code partition(...)} SQL function the app itself
   * uses in {@code extend-partitions.sql}.
   */
  private void extendPartitions() {
    LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(10);
    for (String table : List.of("timed_m1", "timed_m10", "timed_m60",
      "gauge_entry", "gauge_m1", "gauge_m10", "gauge_m60")) {
      db.sqlQuery("select partition(:mode, :table, :count, :schema, :from)")
        .setParameter("mode", "day")
        .setParameter("table", table)
        .setParameter("count", 14)
        .setParameter("schema", "ebean_insight")
        .setParameter("from", from)
        .findList();
    }
  }

  private DApp findOrCreateApp() {
    DApp app = db.find(DApp.class).where().eq("name", APP_NAME).findOne();
    if (app == null) {
      app = new DApp(APP_NAME);
      db.save(app);
    }
    return app;
  }

  private DEnv findOrCreateEnv() {
    DEnv env = db.find(DEnv.class).where().eq("name", ENV_NAME).findOne();
    if (env == null) {
      env = new DEnv(ENV_NAME);
      db.save(env);
    }
    return env;
  }

  private DAppDatabase findOrCreateAppDb(DApp app) {
    DAppDatabase appDb = db.find(DAppDatabase.class)
      .where().eq("app", app).eq("name", "db").findOne();
    if (appDb == null) {
      appDb = new DAppDatabase(app, "db");
      db.save(appDb);
    }
    return appDb;
  }

  /** One {@link DAppMetric} per (label, hash) pair, keyed by label. */
  private Map<String, List<HashMetric>> findOrCreateMetrics(DApp app) {
    Map<String, List<HashMetric>> result = new LinkedHashMap<>();
    for (LabelSpec spec : LABELS) {
      List<HashMetric> hashMetrics = new ArrayList<>(spec.hashes().size());
      for (HashSpec hashSpec : spec.hashes()) {
        String key = seedKey(spec.label(), hashSpec.suffix());
        DAppMetric metric = db.find(DAppMetric.class)
          .where().eq("app", app).eq("key", key).findOne();
        if (metric == null) {
          Map<String, String> tags = Map.of("kind", "orm", "type", spec.type(), "label", spec.label());
          metric = new DAppMetric(app, key, METRIC_NAME, tags, true);
        }

        metric.setLoc(hashSpec.loc());
        metric.setSql(hashSpec.sql());
        db.save(metric);
        hashMetrics.add(new HashMetric(hashSpec, metric));
      }
      result.put(spec.label(), hashMetrics);
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreatePoolMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (String type : List.of("readonly", "main")) {
      String key = "seed-datasource-pool-" + type;
      DAppMetric metric = db.find(DAppMetric.class)
        .where().eq("app", app).eq("key", key).findOne();
      if (metric == null) {
        metric = new DAppMetric(app, key, "datasource.pool.size",
          Map.of("type", type), false);
      }
      db.save(metric);
      result.put(type, metric);
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreatePoolTimingMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (String name : List.of("datasource.pool.wait", "datasource.pool.acquire")) {
      for (String type : List.of("readonly", "main")) {
        String operation = name.endsWith(".wait") ? "wait" : "acquire";
        String key = "seed-pool-v2-" + operation + "-" + type;
        DAppMetric metric = db.find(DAppMetric.class)
          .where().eq("app", app).eq("key", key).findOne();
        if (metric == null) {
          metric = new DAppMetric(app, key, name, Map.of("type", type), false);
        }
        db.save(metric);
        result.put(name + "|" + type, metric);
      }
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreateWebApiMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (String label : List.of("Orders.list", "Orders.create", "Health.get")) {
      String key = "seed-web-api-" + label.toLowerCase().replace('.', '-');
      DAppMetric metric = db.find(DAppMetric.class)
        .where().eq("app", app).eq("key", key).findOne();
      if (metric == null) {
        metric = new DAppMetric(app, key, "web.api", Map.of("label", label), false);
      }
      db.save(metric);
      result.put(label, metric);
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreateDmlMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (DmlSpec spec : DML_LABELS) {
      String key = seedKey(spec.label(), "dml");
      DAppMetric metric = db.find(DAppMetric.class)
        .where().eq("app", app).eq("key", key).findOne();
      if (metric == null) {
        metric = new DAppMetric(app, key, "ebean.dml", Map.of("label", spec.label()), false);
      }
      db.save(metric);
      result.put(spec.label(), metric);
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreateAppComponentMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (AppComponentSpec spec : APP_COMPONENT_LABELS) {
      String key = seedKey(spec.label(), "component");
      DAppMetric metric = db.find(DAppMetric.class)
        .where().eq("app", app).eq("key", key).findOne();
      if (metric == null) {
        metric = new DAppMetric(app, key, "app.component", Map.of("label", spec.label()), false);
      }
      db.save(metric);
      result.put(spec.label(), metric);
    }
    return result;
  }

  private Map<String, DAppMetric> findOrCreateJvmMetrics(DApp app) {
    var result = new LinkedHashMap<String, DAppMetric>();
    for (String name : List.of(
      "jvm.memory.process.vmrss",
      "jvm.memory.heap.used",
      "jvm.cgroup.cpu.usageMicros",
      "jvm.cgroup.cpu.userMicros",
      "jvm.cgroup.cpu.systemMicros",
      "jvm.cgroup.cpu.limitMillicores")) {
      String key = "seed-" + name.replace('.', '-');
      DAppMetric metric = db.find(DAppMetric.class)
        .where().eq("app", app).eq("key", key).findOne();
      if (metric == null) {
        metric = new DAppMetric(app, key, name, Map.of(), false);
      }
      db.save(metric);
      result.put(name, metric);
    }
    return result;
  }

  private List<DAppPod> findOrCreateJvmPods(DApp app) {
    var pods = new ArrayList<DAppPod>(4);
    for (String name : List.of(
      "central-access-demo-1",
      "central-access-demo-2",
      "central-access-demo-3",
      "central-access-demo-4")) {
      DAppPod pod = db.find(DAppPod.class)
        .where().eq("app", app).eq("name", name).findOne();
      if (pod == null) {
        pod = new DAppPod(app, name);
        db.save(pod);
      }
      pods.add(pod);
    }
    return pods;
  }

  /**
   * Deterministic {@code app_metric.key} for a (label, hash) pair, kept
   * under the column's 40-char limit regardless of label length - dot-
   * hierarchy fetch-path labels (e.g. {@code
   * CMachine.findByGid.organisationMachines.thirdPartyIdentifiers.query})
   * can be far longer than the short {@code Type.method} labels this
   * scheme originally assumed. Uses a readable truncated prefix plus a
   * hash-code suffix for uniqueness; the full label is still available
   * (untruncated) via the metric's {@code label} tag.
   */
  private static String seedKey(String label, String hashSuffix) {
    final String suffix = "default".equals(hashSuffix) ? "" : "-" + hashSuffix;
    final String raw = label + suffix;
    final String prefix = raw.length() > 24 ? raw.substring(0, 24) : raw;
    return "seed-" + prefix.replace('.', '_') + "-" + Integer.toHexString(raw.hashCode());
  }

  private void deleteExisting(DApp app) {
    for (String table : List.of("timed_m1", "timed_m10", "timed_m60",
      "gauge_entry", "gauge_m1", "gauge_m10", "gauge_m60")) {
      db.sqlUpdate("delete from ebean_insight." + table + " where app_id = :appId")
        .setParameter("appId", app.getId())
        .execute();
    }
  }

  private int seedPoolGauge(String table, Instant from, Instant to, int bucketMinutes,
                             DEnv env, DApp app, Map<String, DAppMetric> metrics) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, count, total, max, mean)"
      + " values (:eventTime, :metricId, :envId, :appId, 1, :total, :max, :mean)";
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        int minute = t.atZone(ZoneOffset.UTC).getMinute();
        double wave = Math.sin(minute / 60.0 * Math.PI * 2.0) * 2.0;
        for (String type : List.of("readonly", "main")) {
          double value = ("readonly".equals(type) ? 96.0 : 24.0) + wave;
          if (t.isAfter(to.minus(45, ChronoUnit.MINUTES))) {
            value += ("readonly".equals(type) ? 105.0 : 42.0)
              * Math.max(0.0, 1.0 - Duration.between(to.minus(45, ChronoUnit.MINUTES), t).toMinutes() / 20.0);
          }
          db.sqlUpdate(sql)
            .setParameter("eventTime", t)
            .setParameter("metricId", metrics.get(type).getId())
            .setParameter("envId", env.getId())
            .setParameter("appId", app.getId())
            .setParameter("total", value)
            .setParameter("max", value)
            .setParameter("mean", value)
            .execute();
          inserted++;
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private int seedJvmGauge(String table, Instant from, Instant to, int bucketMinutes,
                           DEnv env, DApp app, Map<String, DAppMetric> metrics,
                           List<DAppPod> pods) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, pod_id, value)"
      + " values (:eventTime, :metricId, :envId, :appId, :podId, :value)";
    double[] rssBase = {212.0, 247.0, 247.0, 265.0};
    double[] heapBase = {129.0, 158.0, 166.0, 179.0};
    long[] cpuLimits = {500L, 500L, 750L, 1_000L};
    long[] cpuUsage = new long[pods.size()];
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        double wave = Math.sin(t.getEpochSecond() / 1_800.0);
        for (int i = 0; i < pods.size(); i++) {
          long cpuStep = Math.round((18.0 + i * 2.0 + wave * 4.0)
            * 1_000_000L * 60L * bucketMinutes / 100.0);
          cpuUsage[i] += Math.max(1L, cpuStep);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.memory.process.vmrss"),
            rssBase[i] + wave * 4.0 + i);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.memory.heap.used"),
            heapBase[i] + wave * 3.0 + i);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.cgroup.cpu.usageMicros"),
            cpuUsage[i]);
          long userUsage = Math.round(cpuUsage[i] * 0.78);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.cgroup.cpu.userMicros"),
            userUsage);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.cgroup.cpu.systemMicros"),
            cpuUsage[i] - userUsage);
          insertJvmGauge(sql, t, env, app, pods.get(i), metrics.get("jvm.cgroup.cpu.limitMillicores"),
            cpuLimits[i]);
          inserted += 6;
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private void insertJvmGauge(String sql, Instant eventTime, DEnv env, DApp app, DAppPod pod,
                              DAppMetric metric, double value) {
    db.sqlUpdate(sql)
      .setParameter("eventTime", eventTime)
      .setParameter("metricId", metric.getId())
      .setParameter("envId", env.getId())
      .setParameter("appId", app.getId())
      .setParameter("podId", pod.getId())
      .setParameter("value", value)
      .execute();
  }

  private int seedPoolTiming(String table, Instant from, Instant to, int bucketMinutes,
                             DEnv env, DApp app, Map<String, DAppMetric> metrics) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, count, mean, max, total)"
      + " values (:eventTime, :metricId, :envId, :appId, :count, :mean, :max, :total)";
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        int minute = t.atZone(ZoneOffset.UTC).getMinute();
        double wave = Math.max(0.0, Math.sin(minute / 60.0 * Math.PI * 2.0));
        for (String type : List.of("readonly", "main")) {
          long wait = Math.round(("readonly".equals(type) ? 0.05 : 0.02) + wave * 0.5);
          long acquire = Math.round(("readonly".equals(type) ? 2.0 : 0.8) + wave * 12.0);
          for (var entry : List.of(
            Map.entry("datasource.pool.wait", wait),
            Map.entry("datasource.pool.acquire", acquire))) {
            long total = entry.getValue() * 1000L;
            db.sqlUpdate(sql)
              .setParameter("eventTime", t)
              .setParameter("metricId", metrics.get(entry.getKey() + "|" + type).getId())
              .setParameter("envId", env.getId())
              .setParameter("appId", app.getId())
              .setParameter("count", 1L)
              .setParameter("mean", total)
              .setParameter("max", total)
              .setParameter("total", total)
              .execute();
            inserted++;
          }
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private int seedWebApi(String table, Instant from, Instant to, int bucketMinutes,
                         DEnv env, DApp app, Map<String, DAppMetric> metrics) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, count, mean, max, total)"
      + " values (:eventTime, :metricId, :envId, :appId, :count, :mean, :max, :total)";
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        int minute = t.atZone(ZoneOffset.UTC).getMinute();
        for (var entry : List.of(
          Map.entry("Orders.list", 12L + minute % 5),
          Map.entry("Orders.create", 28L + minute % 9),
          Map.entry("Health.get", 3L + minute % 2))) {
          long count = 10L + minute % 6;
          long mean = entry.getValue() * 1000L;
          long total = mean * count;
          db.sqlUpdate(sql)
            .setParameter("eventTime", t)
            .setParameter("metricId", metrics.get(entry.getKey()).getId())
            .setParameter("envId", env.getId())
            .setParameter("appId", app.getId())
            .setParameter("count", count)
            .setParameter("mean", mean)
            .setParameter("max", mean * 2L)
            .setParameter("total", total)
            .execute();
          inserted++;
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private int seedDml(String table, Instant from, Instant to, int bucketMinutes,
                      DEnv env, DApp app, Map<String, DAppMetric> metrics) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, count, mean, max, total)"
      + " values (:eventTime, :metricId, :envId, :appId, :count, :mean, :max, :total)";
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        var zoned = t.atZone(ZoneOffset.UTC);
        int hour = zoned.getHour();
        DayOfWeek dow = zoned.getDayOfWeek();
        for (DmlSpec spec : DML_LABELS) {
          double activity;
          if (spec.batchHourUtc() != null) {
            activity = hour == spec.batchHourUtc() ? 8.0 : 0.05;
          } else {
            activity = diurnalFactor(hour) * weekendFactor(dow);
          }
          long count = Math.round(spec.weight() * bucketMinutes * activity
            * (0.85 + random.nextDouble() * 0.3));
          if (count <= 0L) {
            if (spec.batchHourUtc() != null) {
              continue;
            }
            count = 1L;
          }
          long mean = Math.round(spec.meanMicros() * (0.8 + random.nextDouble() * 0.4));
          long max = Math.max(mean, Math.round(mean * (1.5 + random.nextDouble() * 1.5)));
          db.sqlUpdate(sql)
            .setParameter("eventTime", t)
            .setParameter("metricId", metrics.get(spec.label()).getId())
            .setParameter("envId", env.getId())
            .setParameter("appId", app.getId())
            .setParameter("count", count)
            .setParameter("mean", mean)
            .setParameter("max", max)
            .setParameter("total", count * mean)
            .execute();
          inserted++;
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private int seedAppComponents(String table, Instant from, Instant to, int bucketMinutes,
                                DEnv env, DApp app, Map<String, DAppMetric> metrics) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, count, mean, max, total)"
      + " values (:eventTime, :metricId, :envId, :appId, :count, :mean, :max, :total)";
    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        var zoned = t.atZone(ZoneOffset.UTC);
        int hour = zoned.getHour();
        DayOfWeek dow = zoned.getDayOfWeek();
        for (AppComponentSpec spec : APP_COMPONENT_LABELS) {
          double activity;
          if (spec.batchHourUtc() != null) {
            activity = hour == spec.batchHourUtc() ? 8.0 : 0.05;
          } else {
            activity = diurnalFactor(hour) * weekendFactor(dow);
          }
          long count = Math.round(spec.weight() * bucketMinutes * activity
            * (0.85 + random.nextDouble() * 0.3));
          if (count <= 0L) {
            if (spec.batchHourUtc() != null) {
              continue;
            }
            count = 1L;
          }
          long mean = Math.round(spec.meanMicros() * (0.8 + random.nextDouble() * 0.4));
          long max = Math.max(mean, Math.round(mean * (1.5 + random.nextDouble() * 1.5)));
          db.sqlUpdate(sql)
            .setParameter("eventTime", t)
            .setParameter("metricId", metrics.get(spec.label()).getId())
            .setParameter("envId", env.getId())
            .setParameter("appId", app.getId())
            .setParameter("count", count)
            .setParameter("mean", mean)
            .setParameter("max", max)
            .setParameter("total", count * mean)
            .execute();
          inserted++;
        }
      }
      txn.commit();
    }
    return inserted;
  }

  /**
   * Insert synthetic rows for one rollup tier, one row per (bucket, label, hash)
   * combination with a non-zero synthetic call count.
   */
  private int seedTier(String table, Instant from, Instant to, int bucketMinutes,
                        DEnv env, DAppDatabase appDb, Map<String, List<HashMetric>> metricsByLabel) {
    String sql = "insert into ebean_insight." + table
      + " (event_time, metric_id, env_id, app_id, db_id, count, mean, max, total)"
      + " values (:eventTime, :metricId, :envId, :appId, :dbId, :count, :mean, :max, :total)";

    int inserted = 0;
    try (Transaction txn = db.beginTransaction()) {
      txn.setBatchMode(true);
      txn.setBatchSize(200);
      for (Instant t = from; !t.isAfter(to); t = t.plus(bucketMinutes, ChronoUnit.MINUTES)) {
        var zoned = t.atZone(ZoneOffset.UTC);
        int hour = zoned.getHour();
        DayOfWeek dow = zoned.getDayOfWeek();
        for (LabelSpec spec : LABELS) {
          for (HashMetric hashMetric : metricsByLabel.get(spec.label())) {
            Bucket bucket = computeBucket(spec, hashMetric.spec(), bucketMinutes, hour, dow);
            if (bucket == null) {
              continue;
            }
            db.sqlUpdate(sql)
              .setParameter("eventTime", t)
              .setParameter("metricId", hashMetric.metric().getId())
              .setParameter("envId", env.getId())
              .setParameter("appId", hashMetric.metric().getApp().getId())
              .setParameter("dbId", appDb.getId())
              .setParameter("count", bucket.count)
              .setParameter("mean", bucket.mean)
              .setParameter("max", bucket.max)
              .setParameter("total", bucket.total)
              .execute();
            inserted++;
          }
        }
      }
      txn.commit();
    }
    return inserted;
  }

  private record Bucket(long count, long mean, long max, long total) {
  }

  private Bucket computeBucket(LabelSpec spec, HashSpec hashSpec, int bucketMinutes, int hour, DayOfWeek dow) {
    double jitter = 0.85 + random.nextDouble() * 0.3;
    double dayFactor;
    if (spec.batchHourUtc() != null) {
      dayFactor = (hour == spec.batchHourUtc()) ? 8.0 : 0.05;
    } else {
      dayFactor = diurnalFactor(hour) * weekendFactor(dow);
    }
    long count = Math.round(spec.weight() * hashSpec.share() * bucketMinutes * dayFactor * jitter);
    if (count <= 0) {
      return null;
    }
    long meanMicros = (long) (spec.meanMicros() * hashSpec.meanMultiplier() * (0.8 + random.nextDouble() * 0.4));
    long total = count * meanMicros;
    long mean = Math.floorDiv(total, count);
    long max = (long) (meanMicros * (1.4 + random.nextDouble() * 1.8));
    return new Bucket(count, mean, Math.max(max, mean), total);
  }

  /** Business-hours "wave" - peaks around 13:00 UTC, low overnight. */
  private double diurnalFactor(int hourUtc) {
    double x = (hourUtc - 13.0) / 6.5;
    return 0.15 + 0.85 * Math.exp(-x * x);
  }

  /** Reduced weekend traffic. */
  private double weekendFactor(DayOfWeek dow) {
    return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? 0.35 : 1.0;
  }

  private static Instant roundDown(Instant instant, int minutes) {
    long epochMinute = instant.getEpochSecond() / 60;
    long rounded = (epochMinute / minutes) * minutes;
    return Instant.ofEpochSecond(rounded * 60);
  }

  /**
   * Seed a handful of {@code query_plan} captures per hash so the
   * metric-detail drill-down's "recently captured plans" table has data.
   * Most hashes get a single steady capture; a hash with a
   * {@code regressedPlanShapeHash} set gets an older baseline capture
   * followed by a more recent capture using the regressed shape, so the
   * "shape changed" flag has a real example to show.
   */
  private int seedQueryPlans(DApp app, DEnv env, Map<String, List<HashMetric>> metricsByLabel) {
    db.sqlUpdate("delete from ebean_insight.query_plan where app_id = :appId")
      .setParameter("appId", app.getId())
      .execute();

    Instant now = Instant.now();
    int inserted = 0;
    for (LabelSpec spec : LABELS) {
      for (HashMetric hashMetric : metricsByLabel.get(spec.label())) {
        HashSpec hs = hashMetric.spec();
        long queryTimeMicros = (long) (spec.meanMicros() * hs.meanMultiplier());
        if (hs.regressedPlanShapeHash() == null) {
          savePlan(app, env, hashMetric.metric(), spec, hs, hs.planShapeHash(), hs.plan(),
            queryTimeMicros, now.minus(6, ChronoUnit.HOURS));
          inserted++;
        } else {
          savePlan(app, env, hashMetric.metric(), spec, hs, hs.planShapeHash(), hs.plan(),
            queryTimeMicros, now.minus(3, ChronoUnit.DAYS));
          savePlan(app, env, hashMetric.metric(), spec, hs, hs.regressedPlanShapeHash(), hs.regressedPlan(),
            (long) (queryTimeMicros * 3.5), now.minus(3, ChronoUnit.HOURS));
          inserted += 2;
        }
      }
    }
    return inserted;
  }

  private void savePlan(DApp app, DEnv env, DAppMetric metric, LabelSpec spec, HashSpec hashSpec,
                        String planShapeHash, String plan, long queryTimeMicros, Instant whenCaptured) {
    DQueryPlan queryPlan = new DQueryPlan(app, env, metric);
    queryPlan.setHash(metric.getKey());
    queryPlan.setName(METRIC_NAME);
    queryPlan.setKind("orm");
    queryPlan.setType(spec.type());
    queryPlan.setLabel(spec.label());
    queryPlan.setQueryTimeMicros(queryTimeMicros);
    queryPlan.setCaptureCount(5 + random.nextInt(40));
    queryPlan.setCaptureMicros(queryTimeMicros + 200 + random.nextInt(500));
    queryPlan.setWhenCaptured(whenCaptured);
    queryPlan.setSql(hashSpec.sql());
    queryPlan.setPlan(plan);
    queryPlan.setPlanShape(plan);
    queryPlan.setPlanShapeHash(planShapeHash);
    db.save(queryPlan);
  }
}
