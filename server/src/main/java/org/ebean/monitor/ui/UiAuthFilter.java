package org.ebean.monitor.ui;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class UiAuthFilter implements HttpFilter {
  private final UiAuthService service;

  UiAuthFilter(UiAuthService service) {
    this.service = service;
  }

  @Override
  public void filter(Context context, FilterChain chain) {
    String path = context.path();
    if (!path.startsWith("/ux")) {
      chain.proceed();
      return;
    }
    String sessionId = context.cookie(service.cookieName());
    var session = service.authenticate(sessionId);
    if (session.isPresent()) {
      String userSub = session.get().userSub();
      if (userSub != null) {
        context.attribute("security.principal", userSub);
      }
      chain.proceed();
      return;
    }
    if ("/ux/top/data".equals(path)) {
      throw new HttpResponseException(401, "UI authentication required");
    }
    String returnPath = context.path();
    if (context.queryString() != null && !context.queryString().isBlank()) {
      returnPath += "?" + context.queryString();
    }
    context.redirect("/auth/login?return=" + URLEncoder.encode(
      service.safeReturnPath(returnPath), StandardCharsets.UTF_8));
  }
}
