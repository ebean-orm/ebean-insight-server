package org.ebean.monitor.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.jex.http.Context;

@Controller
final class RootController {

  @Get("/")
  void home(Context context) {
    context.redirect("/ux");
  }
}
