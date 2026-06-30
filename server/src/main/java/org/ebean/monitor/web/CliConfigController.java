package org.ebean.monitor.web;

import io.avaje.config.Config;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import org.jspecify.annotations.Nullable;

/**
 * Returns public OAuth2 client config so the CLI can bootstrap itself from a
 * single URL without requiring manual {@code insight config set} calls.
 *
 * <p>This endpoint intentionally requires no authentication — it exposes only
 * public PKCE client settings (domain, client-id, scope) that are visible in
 * every OAuth2 redirect URL anyway.
 *
 * <p>Values are sourced from:
 * <ul>
 *   <li>{@code insight.cli.auth.domain}</li>
 *   <li>{@code insight.cli.auth.client-id}</li>
 *   <li>{@code insight.cli.auth.scope}</li>
 * </ul>
 */
@Controller
@Path("/api/cli-config")
final class CliConfigController {

  @Get
  CliConfig get() {
    return new CliConfig(
        trimToNull(Config.getNullable("insight.cli.auth.domain")),
        trimToNull(Config.getNullable("insight.cli.auth.client-id")),
        trimToNull(Config.getNullable("insight.cli.auth.scope")));
  }

  private static @Nullable String trimToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
