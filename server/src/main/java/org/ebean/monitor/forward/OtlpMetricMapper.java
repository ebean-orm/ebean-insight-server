package org.ebean.monitor.forward;

import io.avaje.json.JsonWriter;
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Singleton;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.api.MetricRequest;

import java.io.StringWriter;

/**
 * Translates a {@link MetricRequest} into an OTLP/HTTP JSON payload for
 * {@code POST /v1/metrics}.
 *
 * <p>Writes JSON token-by-token via {@link JsonWriter} (from avaje-json-core,
 * obtained via {@link Jsonb#writer}). Reflection-free, native-image safe,
 * and avoids allocating an intermediate object tree.
 *
 * <p>Each timer is emitted as 3 metrics:
 * <ul>
 *   <li>{@code <name>.count} — Sum (delta, monotonic), Long, unit={@code 1}</li>
 *   <li>{@code <name>.total} — Sum (delta, monotonic), Long, unit={@code us}</li>
 *   <li>{@code <name>.max}   — Gauge, Long, unit={@code us}</li>
 * </ul>
 * Each non-timed metric (value) is emitted as a single Gauge.
 *
 * <p>Resource attributes:
 * {@code service.name=appName}, {@code deployment.environment.name=environment},
 * {@code service.instance.id=instanceId}, {@code service.version=version},
 * {@code service.namespace=namespace} (configured), {@code insight.key=key} (if present).
 *
 * <p>OTLP/HTTP JSON quirk: int64 fields ({@code startTimeUnixNano},
 * {@code timeUnixNano}, {@code asInt}) are emitted as JSON strings per the
 * protobuf-JSON mapping spec.
 */
@Singleton
public final class OtlpMetricMapper {

  private static final String SCOPE_NAME = "ebean-insight-forwarder";
  /** AGGREGATION_TEMPORALITY_DELTA. */
  private static final int DELTA = 1;

  private final String namespace;
  private final Jsonb jsonb;

  public OtlpMetricMapper(ForwardConfig config, Jsonb jsonb) {
    this.namespace = config.namespace() == null ? "" : config.namespace();
    this.jsonb = jsonb;
  }

  String build(MetricRequest req, long reportingPeriodNanos) {
    long endNano = (req.eventTime > 0 ? req.eventTime : System.currentTimeMillis()) * 1_000_000L;
    long startNano = (req.startEventTime > 0)
      ? req.startEventTime * 1_000_000L
      : endNano - reportingPeriodNanos;

    var sw = new StringWriter(2048);
    try (JsonWriter w = jsonb.writer(sw)) {
      w.beginObject();
      w.name("resourceMetrics");
      w.beginArray();
      writeResourceMetrics(w, req, startNano, endNano);
      w.endArray();
      w.endObject();
    }
    return sw.toString();
  }

  private void writeResourceMetrics(JsonWriter w, MetricRequest req, long startNano, long endNano) {
    w.beginObject();
    writeResource(w, req);
    w.name("scopeMetrics");
    w.beginArray();
    writeScopeMetrics(w, req, startNano, endNano);
    w.endArray();
    w.endObject();
  }

  private void writeScopeMetrics(JsonWriter w, MetricRequest req, long startNano, long endNano) {
    w.beginObject();
    w.name("scope");
    w.beginObject();
    w.name("name");
    w.value(SCOPE_NAME);
    w.endObject();
    w.name("metrics");
    w.beginArray();
    if (req.metrics != null) {
      for (MetricData md : req.metrics) {
        writeMetric(w, md, startNano, endNano, null);
      }
    }
    if (req.dbs != null) {
      for (MetricDbData db : req.dbs) {
        if (db == null || db.metrics == null) {
          continue;
        }
        for (MetricData md : db.metrics) {
          writeMetric(w, md, startNano, endNano, db.db);
        }
      }
    }
    w.endArray();
    w.endObject();
  }

  private static final java.util.Set<String> RESERVED_KEYS = java.util.Set.of(
    "service.name", "service.version", "service.instance.id",
    "deployment.environment.name", "deployment.environment");

  private void writeResource(JsonWriter w, MetricRequest req) {
    w.name("resource");
    w.beginObject();
    w.name("attributes");
    w.beginArray();
    writeAttr(w, "service.name", req.appName);
    writeAttr(w, "deployment.environment.name", req.environment);
    writeAttr(w, "service.instance.id", req.instanceId);
    writeAttr(w, "service.version", req.version);
    boolean clientHasNamespace = req.resAttrs != null && req.resAttrs.containsKey("service.namespace");
    if (!namespace.isEmpty() && !clientHasNamespace) {
      writeAttr(w, "service.namespace", namespace);
    }
    if (req.resAttrs != null) {
      for (var e : req.resAttrs.entrySet()) {
        if (RESERVED_KEYS.contains(e.getKey())) {
          continue;
        }
        writeAttr(w, e.getKey(), e.getValue());
      }
    }
    w.endArray();
    w.endObject();
  }

  private void writeMetric(JsonWriter w, MetricData md, long startNano, long endNano, String db) {
    if (md == null || md.name == null || md.name.isEmpty()) {
      return;
    }
    var mapped = MetricNameMapper.map(md.name);
    boolean hasTimer = md.count != null && md.total != null;
    if (!hasTimer && md.value == null) {
      return;
    }
    if (hasTimer) {
      writeSum(w, mapped.name() + ".count", "1", md.count, startNano, endNano, mapped.attrs(), db);
      writeSum(w, mapped.name() + ".total", "us", md.total, startNano, endNano, mapped.attrs(), db);
      if (md.max != null) {
        writeGaugeLong(w, mapped.name() + ".max", "us", md.max, endNano, mapped.attrs(), db);
      }
    } else {
      writeGaugeDouble(w, mapped.name(), "", md.value, endNano, mapped.attrs(), db);
    }
  }

  private void writeSum(JsonWriter w, String name, String unit, long value,
                        long startNano, long endNano, String[] attrs, String db) {
    w.beginObject();
    w.name("name");
    w.value(name);
    if (!unit.isEmpty()) {
      w.name("unit");
      w.value(unit);
    }
    w.name("sum");
    w.beginObject();
    w.name("dataPoints");
    w.beginArray();
    w.beginObject();
    writeDpAttributes(w, attrs, db);
    int64(w, "startTimeUnixNano", startNano);
    int64(w, "timeUnixNano", endNano);
    int64(w, "asInt", value);
    w.endObject();
    w.endArray();
    w.name("aggregationTemporality");
    w.value(DELTA);
    w.name("isMonotonic");
    w.value(true);
    w.endObject();
    w.endObject();
  }

  private void writeGaugeLong(JsonWriter w, String name, String unit, long value,
                              long endNano, String[] attrs, String db) {
    w.beginObject();
    w.name("name");
    w.value(name);
    if (!unit.isEmpty()) {
      w.name("unit");
      w.value(unit);
    }
    w.name("gauge");
    w.beginObject();
    w.name("dataPoints");
    w.beginArray();
    w.beginObject();
    writeDpAttributes(w, attrs, db);
    int64(w, "timeUnixNano", endNano);
    int64(w, "asInt", value);
    w.endObject();
    w.endArray();
    w.endObject();
    w.endObject();
  }

  private void writeGaugeDouble(JsonWriter w, String name, String unit, double value,
                                long endNano, String[] attrs, String db) {
    w.beginObject();
    w.name("name");
    w.value(name);
    if (!unit.isEmpty()) {
      w.name("unit");
      w.value(unit);
    }
    w.name("gauge");
    w.beginObject();
    w.name("dataPoints");
    w.beginArray();
    w.beginObject();
    writeDpAttributes(w, attrs, db);
    int64(w, "timeUnixNano", endNano);
    w.name("asDouble");
    w.value(value);
    w.endObject();
    w.endArray();
    w.endObject();
    w.endObject();
  }

  private void writeDpAttributes(JsonWriter w, String[] attrs, String db) {
    w.name("attributes");
    w.beginArray();
    for (int i = 0; i + 1 < attrs.length; i += 2) {
      writeAttr(w, attrs[i], attrs[i + 1]);
    }
    if (db != null && !db.isEmpty()) {
      writeAttr(w, "db", db);
    }
    w.endArray();
  }

  private static void writeAttr(JsonWriter w, String key, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    w.beginObject();
    w.name("key");
    w.value(key);
    w.name("value");
    w.beginObject();
    w.name("stringValue");
    w.value(value);
    w.endObject();
    w.endObject();
  }

  /** Emit an int64 field as a JSON string (per OTLP/HTTP JSON spec). */
  private static void int64(JsonWriter w, String name, long value) {
    w.name(name);
    w.value(Long.toString(value));
  }
}
