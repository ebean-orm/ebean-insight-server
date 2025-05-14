package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;
import io.jstach.jstache.JStacheFormatterTypes;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.web.TimeEvents;
import org.ebean.monitor.web.UIChartUtil;

import java.time.Instant;
import java.util.List;

public class Page {

  @JStache(path = "index")
  public record Index(){}

  @JStache(path = "app")
  public record App(DApp app){}

  @JStache(path = "app-metric")
  public record AppMetric(DAppMetric appMetric){}

  @JStache(path = "view-plan")
  public record ViewPlan(String rawSql, String rawPlan){}

  @JStacheFormatterTypes(types = Instant.class)
  @JStache(path = "dash")
  public record Dash(List<TimeEvents> events, List<TimeEvents> events2){

    public String arrayData() {
      return UIChartUtil.eventsAsArray(events);
    }

    public String arrayData2() {
      return UIChartUtil.eventsAsArray(events2);
    }
  }

}
