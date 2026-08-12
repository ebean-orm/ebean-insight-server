package org.ebean.monitor.ui;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Post;
import io.avaje.http.api.Path;
import io.avaje.jex.http.Context;

@Controller
@Path("/auth")
final class UiAuthController {
  private final UiAuthService service;

  UiAuthController(UiAuthService service) {
    this.service = service;
  }

  @Get("login")
  void login(Context context) {
    if (!service.enabled()) {
      context.writeEmpty(404);
      return;
    }
    String previousSession = context.cookie(service.cookieName());
    UiLoginStart login = service.beginLogin(context.queryParam("return"), previousSession);
    context.cookie(service.loginCookie(login.state()));
    context.redirect(login.url());
  }

  @Get("callback")
  void callback(Context context) {
    if (!service.enabled()) {
      context.writeEmpty(404);
      return;
    }
    String error = context.queryParam("error");
    if (error != null && !error.isBlank()) {
      context.cookie(service.expiredLoginCookie());
      context.status(400).text("OAuth login failed");
      return;
    }
    String state = context.queryParam("state");
    String code = context.queryParam("code");
    if (state == null || state.isBlank() || code == null || code.isBlank()) {
      context.cookie(service.expiredLoginCookie());
      context.status(400).text("Invalid OAuth callback");
      return;
    }
    try {
      UiLoginResult result = service.completeLogin(
        state, context.cookie(service.loginCookieName()), code);
      context.cookie(service.expiredLoginCookie());
      context.cookie(service.sessionCookie(result.session().id()));
      context.redirect(result.returnPath());
    } catch (UiTokenException e) {
      context.cookie(service.expiredLoginCookie());
      context.status(400).text("OAuth login failed");
    }
  }

  @Post("logout")
  void logout(Context context) {
    service.logout(context.cookie(service.cookieName()));
    context.cookie(service.expiredSessionCookie());
    context.redirect("/ux");
  }
}
