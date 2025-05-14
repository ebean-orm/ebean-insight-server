package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.util.List;

@Json
public class ListResponse<T> {

  private final List<T> list;

  public ListResponse(List<T> list) {
    this.list = list;
  }

  public List<T> getList() {
    return list;
  }

  @Override
  public String toString() {
    return "ListResponse{" +
      "list=" + list +
      '}';
  }
}
