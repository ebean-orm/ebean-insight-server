package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.model.Env;

@Controller
public final class V1EnvsController implements EnvsApi {

  private final V1QueryService service;

  public V1EnvsController(V1QueryService service) {
    this.service = service;
  }

  @Override
  public List<Env> listEnvs() {
    return service.listEnvs();
  }
}
