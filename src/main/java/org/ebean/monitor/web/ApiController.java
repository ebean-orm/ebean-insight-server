package org.ebean.monitor.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.jex.http.NotFoundException;
import org.ebean.monitor.api.App;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.api.DataPoint;
import org.ebean.monitor.api.Env;
import org.ebean.monitor.api.ListResponse;
import org.ebean.monitor.cleanup.CleanupPartitions;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DGaugeRollupM1;
import org.ebean.monitor.domain.DTimedRollupM1;
import org.ebean.monitor.domain.query.QDGaugeRollupM1;
import org.ebean.monitor.domain.query.QDTimedRollupM1;

import java.util.ArrayList;
import java.util.List;

@Controller
@Path("/api")
class ApiController {

  @Post("cleanup")
  String cleanup() {
    final long droppedPartitions = new CleanupPartitions().run();
    return "partitions " + droppedPartitions;
  }

  /**
   * Return the environments for the given org.
   */
  @Get("env")
  ListResponse<Env> getEnvs() {
    final List<Env> envs = DEnv.find.findAll();
    return new ListResponse<>(envs);
  }

  /**
   * Return the applications for the given org.
   */
  @Get("app")
  ListResponse<App> getApps() {
    final List<App> apps = DApp.find.findAll();
    return new ListResponse<>(apps);
  }

  /**
   * Return the application metrics.
   */
  @Get("app/{id}/metric")
  ListResponse<AppMetric> getAppMetrics(long id) {
    final DApp app = DApp.find.ref(id);
    return new ListResponse<>(DAppMetric.find.byApp(app));
  }

  /**
   * Return Aggregate metric for an org, env, app.
   */
  @Get("env/{envName}/app/{appId}/global/{metricName}")
  ListResponse<DataPoint> getAggregateDbTime(String envName, long appId, String metricName, Integer maxRows) {
    final DEnv env = DEnv.find.byName(envName);
    if (env == null) {
      throw new NotFoundException("No such environment");
    }
    final DAppMetric metric = DAppMetric.find.globalByName(metricName);
    if (metric == null) {
      throw new NotFoundException("No such metric");
    }
    final DApp app = DApp.find.ref(appId);
    int max = (maxRows == null) ? 60 * 3 : maxRows;

    if (metricName.startsWith("jvm")) {
      final QDGaugeRollupM1 g = QDGaugeRollupM1.alias();
      final List<DGaugeRollupM1> list = new QDGaugeRollupM1()
        .select(g.eventTime, g.total)
        .app.eq(app)
        .env.eq(env)
        .metric.eq(metric)
        .eventTime.desc()
        .setMaxRows(max)
        .findList();

      List<DataPoint> data = new ArrayList<>(list.size());
      for (DGaugeRollupM1 timed : list) {
        data.add(new DataPoint(timed.getEventTime(), timed.getTotal().longValue()));
      }
      return new ListResponse<>(data);
    }

    final QDTimedRollupM1 t = QDTimedRollupM1.alias();
    final List<DTimedRollupM1> list = new QDTimedRollupM1()
      .select(t.eventTime, t.total)
      .app.eq(app)
      .env.eq(env)
      .metric.eq(metric)
      .eventTime.desc()
      .setMaxRows(max)
      .findList();

    List<DataPoint> data = new ArrayList<>(list.size());
    for (DTimedRollupM1 timed : list) {
      data.add(new DataPoint(timed.getEventTime(), timed.getTotal()));
    }
    return new ListResponse<>(data);
  }
}
