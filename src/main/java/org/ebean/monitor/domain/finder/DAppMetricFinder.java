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
   * Return the global metric by name.
   */
  public DAppMetric globalByName(String name) {
    return new QDAppMetric()
      .app.isNull()
      .name.eq(name)
      .findOne();
  }

  /**
   * Return all the metrics with rollupGroup for use in rollup processing.
   */
  public List<DAppMetric> forRollup() {
    final QDAppMetric m = QDAppMetric.alias();
    return new QDAppMetric()
      .select(m.name, m.rollupGroup, m.app)
      .findList();
  }
}
