package org.ebean.monitor.domain.finder;

import io.ebean.Finder;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.query.QDAppMetric;

import java.util.List;

public class DAppMetricFinder extends Finder<Integer,DAppMetric> {

  /**
   * Construct using the default Database.
   */
  public DAppMetricFinder() {
    super(DAppMetric.class);
  }

  public List<AppMetric> byApp(DApp app) {
    final QDAppMetric m = QDAppMetric.alias();
    return new QDAppMetric()
      .select(m.id, m.name, m.loc, m.sql)
      .app.eq(app)
      .name.desc()
      .asDto(AppMetric.class)
      .findList();
  }

  /**
   * Return all metric variants (different hashes) for the given app sharing
   * a label (the metric {@code name}). Useful when a query label maps to
   * multiple distinct SQL variants.
   */
  public List<AppMetric> byAppLabel(DApp app, String label) {
    final QDAppMetric m = QDAppMetric.alias();
    return new QDAppMetric()
      .select(m.id, m.name, m.loc, m.sql)
      .app.eq(app)
      .name.eq(label)
      .name.desc()
      .asDto(AppMetric.class)
      .findList();
  }

  /**
   * Return the metric for the given app + hash key, or null if not found.
   */
  public DAppMetric byAppHash(DApp app, String hash) {
    return new QDAppMetric()
      .app.eq(app)
      .key.eq(hash)
      .findOne();
  }

  /**
   * Return the global metric by name.
   */
  public DAppMetric globalByName(String name) {
    return new QDAppMetric()
      .app.isNull()
      .name.eq(name)
      .findOne();
  }

}
