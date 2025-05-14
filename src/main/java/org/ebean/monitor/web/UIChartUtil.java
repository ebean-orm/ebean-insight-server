package org.ebean.monitor.web;

import java.util.List;

public final class UIChartUtil {

  public static String eventsAsArray(List<TimeEvents> events) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      var distanceByTime = events.get(i);
      sb.append('{')
        .append("x:\"").append(distanceByTime.time()).append('"')
        .append(",y:").append(distanceByTime.value())
        .append('}');
    }
    sb.append(']');
    return sb.toString();
  }
}
