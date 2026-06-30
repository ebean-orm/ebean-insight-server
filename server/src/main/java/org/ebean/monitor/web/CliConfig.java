package org.ebean.monitor.web;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * Bootstrap configuration returned to the CLI via {@code GET /api/cli-config}.
 * Contains only public OAuth2 client settings (no secrets) so the CLI can
 * self-configure from a single URL without manual {@code insight config set} calls.
 */
@Json
record CliConfig(
    @Nullable String authDomain,
    @Nullable String authClientId,
    @Nullable String authScope) {
}
