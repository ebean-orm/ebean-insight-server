package org.ebean.monitor.usage;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;

final class UserUsageFilter implements HttpFilter {

  private final UserUsageService usage;

  UserUsageFilter(UserUsageService usage) {
    this.usage = usage;
  }

  @Override
  public void filter(Context context, FilterChain chain) {
    String path = context.path();
    if (!UserUsageService.isTrackedPath(path)) {
      chain.proceed();
      return;
    }
    long started = System.nanoTime();
    try {
      chain.proceed();
    } finally {
      String user = context.attribute(UserUsageService.PRINCIPAL_ATTRIBUTE);
      if (user != null) {
        usage.record(user, context.method(), path, System.nanoTime() - started);
      }
    }
  }
}
