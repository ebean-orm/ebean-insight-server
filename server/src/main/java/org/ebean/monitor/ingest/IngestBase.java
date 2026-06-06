package org.ebean.monitor.ingest;

import io.ebean.DB;
import io.ebean.Transaction;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.config.GlobalMetrics;
import org.ebean.monitor.domain.BaseEntry;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppDatabase;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class IngestBase {

  private static final Logger log = LoggerFactory.getLogger(IngestBase.class);

  final IngestHeader header;

  private final DApp metricApp;
  private final DAppDatabase db;

  private final Map<String, IngestEntry> entryMap = new LinkedHashMap<>();
  private final Map<String, DAppMetric> metricMap = new LinkedHashMap<>();

  IngestBase(IngestHeader header, DAppDatabase db) {
    this.header = header;
    this.metricApp = header.getApp();
    this.db = db;
  }

  abstract void createMetricEntries();

  void createMetricEntries(List<MetricData> metrics) {
    loadEntryMap(metrics);
    loadMetricMap();
    createMetrics();
  }

  private void loadEntryMap(List<MetricData> metrics) {
    for (MetricData data : metrics) {
      final String key = MetricKey.of(data);
      final IngestEntry dup = entryMap.put(key, new IngestEntry(key, data));
      if (dup != null) {
        log.error("Lost metric due to duplicate metric key? " + key);
      }
    }
  }

  private void loadMetricMap() {
    this.metricMap.putAll(GlobalMetrics.get());
    this.metricMap.putAll(lookupExistingMetrics());
    createMissingMetrics();
  }

  /**
   * Assign DMetric and return list for persisting.
   */
  private void createMetrics() {

    for (IngestEntry ingestEntry : entryMap.values()) {
      final DAppMetric metric = metricMap.get(ingestEntry.getKey());
      if (metric == null) {
        log.error("Failed metric lookup for key: " + ingestEntry.getKey());
      } else {
        final BaseEntry entry = createMetricEntry(ingestEntry.assignMetric(metric));
        entry.save();
      }
    }
  }

  private Set<String> missingKeys() {
    Set<String> missingKeys = new HashSet<>();
    for (String key : entryMap.keySet()) {
      if (!metricMap.containsKey(key)) {
        missingKeys.add(key);
      }
    }
    return missingKeys;
  }

  private BaseEntry createMetricEntry(IngestEntry ingestEntry) {
    return header.createMetricEntry(ingestEntry, db);
  }

  private void createMissingMetrics() {
    final Map<String, DAppMetric> newMetrics = createMissing();
    DB.saveAll(newMetrics.values());
    // flush new metrics to DB to make sure we have the keys
    Transaction.current().flush();
    metricMap.putAll(newMetrics);
  }

  private Map<String, DAppMetric> createMissing() {
    return createNewMetrics(entriesFor(missingKeys()));
  }

  private List<IngestEntry> entriesFor(Set<String> missingKeys) {
    List<IngestEntry> list = new ArrayList<>(missingKeys.size());
    for (String key : missingKeys) {
      list.add(entryMap.get(key));
    }
    return list;
  }

  private Map<String, DAppMetric> createNewMetrics(List<IngestEntry> missingEntries) {
    Map<String, DAppMetric> map = new HashMap<>();
    for (IngestEntry entry : missingEntries) {
      final DAppMetric metric = createMetric(entry);
      map.put(metric.getKey(), metric);
    }
    return map;
  }

  private DAppMetric createMetric(IngestEntry entry) {

    final MetricData data = entry.getData();
    DAppMetric metric = new DAppMetric(metricApp, entry.getKey(), data.name);
    metric.setRollupGroup(DeriveGroup.of(data.name));
    metric.setSql(data.sql);
    metric.setLoc(data.loc);
    return metric;
  }

  private Map<String, DAppMetric> lookupExistingMetrics() {
    Set<String> keys = new HashSet<>(entryMap.keySet());
    // remove all the global metrics (already loaded)
    keys.removeAll(metricMap.keySet());
    return new QDAppMetric()
      .app.eq(metricApp)
      .key.in(keys)
      .key.asMapKey()
      .setUseCache(true)
      .findMap();
  }

}
