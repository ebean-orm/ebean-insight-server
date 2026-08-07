package org.ebean.monitor.web.view;

import java.util.List;

public record Breadcrumb(List<Item> crumbs) {

  public static Breadcrumb EMPTY = new Breadcrumb(List.of());

  public record Item(String href, String active, String name) {
    public Item(String href, String name) {
      this(href, "", name);
    }

    public Item(String name) {
      this(null, "active", name);
    }
  }


}
