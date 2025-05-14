package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

@Json
public class AppMetric {

  private final long id;
  private final String name;
  private String key;
  private String loc;
  private String sql;

  public AppMetric(long id, String name) {
    this.id = id;
    this.name = name;
  }

  public long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getLoc() {
    return loc;
  }

  public void setLoc(String loc) {
    this.loc = loc;
  }

  public String getSql() {
    return sql;
  }

  public void setSql(String sql) {
    this.sql = sql;
  }
}
