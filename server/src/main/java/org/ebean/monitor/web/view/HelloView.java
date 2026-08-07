package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

@JStache(path = "hello")
public record HelloView(
  Breadcrumb breadcrumb,
  String name
) {
}
