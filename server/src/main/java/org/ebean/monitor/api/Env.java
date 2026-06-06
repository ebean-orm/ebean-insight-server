package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

@Json
public class Env {

  private final String name;

  public Env(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return "Env{" +
      "name='" + name + '\'' +
      '}';
  }
}
