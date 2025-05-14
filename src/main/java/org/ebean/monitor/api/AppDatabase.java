package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

@Json
public class AppDatabase {

  private final String name;

  public AppDatabase(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

}
