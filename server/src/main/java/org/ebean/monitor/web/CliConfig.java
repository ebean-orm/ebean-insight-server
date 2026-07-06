package org.ebean.monitor.web;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * Bootstrap configuration returned to the CLI via {@code GET /api/cli-config}.
 * Contains only public OAuth2 client settings (no secrets) so the CLI can
 * self-configure from a single URL without manual {@code insight config set} calls.
 *
 * <p>{@code authDomain} is used for Cognito (Hosted-UI domain); {@code authTenantId}
 * is used instead for Microsoft Entra ID (the CLI derives the v2.0 authorize/token
 * endpoints from the tenant id). Set whichever matches the configured provider.
 */
@Json
record CliConfig(
    @Nullable String authDomain,
    @Nullable String authClientId,
    @Nullable String authScope,
    @Nullable String authTenantId) {
}
