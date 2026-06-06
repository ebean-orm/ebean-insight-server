package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.time.Instant;

@Json
public class DataPoint {
  long x;
  long y;

  public DataPoint() {
  }

  public DataPoint(Instant eventTime, Long value) {
    this.x = eventTime.toEpochMilli();
    this.y = value;
  }

  public long getX() {
    return x;
  }

  public void setX(long x) {
    this.x = x;
  }

  public long getY() {
    return y;
  }

  public void setY(long y) {
    this.y = y;
  }
}
