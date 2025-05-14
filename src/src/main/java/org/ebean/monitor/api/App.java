package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

@Json
public class App {

  private final long id;
  private final String name;

  public App(long id, String name) {
    this.id = id;
    this.name = name;
  }

  public long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return "App{" +
      "name='" + name + '\'' +
      '}';
  }
}
