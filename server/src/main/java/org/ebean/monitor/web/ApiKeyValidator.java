package org.ebean.monitor.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Validates a shared-secret API key presented as an {@code Authorization: Bearer}
 * token on the {@code /v1} API, as an alternative to a Cognito JWT.
 * <p>
 * Mirrors {@link IngestKeyValidator} but for the read-side {@code /v1} API. It is
 * wired into the JWT auth filter via its {@code bearerAuthoriser} hook (see
 * {@code AuthConfiguration}) rather than being checked in a controller.
 * <p>
 * When no keys are configured ({@code insight.api.key} unset) the validator is
 * <em>disabled</em> and {@link #principalFor(String)} always returns {@code null}
 * so requests fall through to JWT verification. When one or more keys are
 * configured (comma separated for rotation) a request presenting a matching
 * bearer token is authenticated as the {@value #PRINCIPAL} principal.
 */
public final class ApiKeyValidator {

  /** Principal name registered for a request authenticated by an API key. */
  public static final String PRINCIPAL = "api-key";

  private final List<byte[]> keys;

  public ApiKeyValidator(List<String> keys) {
    this.keys = keys.stream()
      .map(k -> k.getBytes(StandardCharsets.UTF_8))
      .toList();
  }

  /** True when at least one API key is configured. */
  public boolean enabled() {
    return !keys.isEmpty();
  }

  /**
   * Return the principal name when the bearer token matches a configured API key
   * (constant-time compare), otherwise {@code null} so the caller falls through
   * to JWT verification.
   * <p>
   * Signature matches {@code io.avaje.oauth2.core.jwt.BearerAuthoriser} so this
   * can be supplied as a method reference.
   */
  public String principalFor(String token) {
    return matches(token) ? PRINCIPAL : null;
  }

  private boolean matches(String provided) {
    if (provided == null || provided.isEmpty()) {
      return false;
    }
    byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
    for (byte[] allowed : keys) {
      if (MessageDigest.isEqual(providedBytes, allowed)) {
        return true;
      }
    }
    return false;
  }
}
