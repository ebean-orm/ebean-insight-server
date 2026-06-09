package org.ebean.monitor.web;

import io.avaje.jex.http.HttpResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Validates the {@code Insight-Key} shared secret presented by app forwarders
 * on the {@code /api/ingest} endpoints.
 * <p>
 * When no keys are configured ({@code insight.ingest.key} unset) the validator
 * is <em>disabled</em> and every ingest request is accepted — protected only by
 * network isolation, exactly as before. When one or more keys are configured a
 * request must present a matching {@code Insight-Key} header, otherwise
 * {@link #validate(String)} throws HTTP 401.
 * <p>
 * Multiple keys are supported (comma separated config) to allow key rotation:
 * old and new keys both validate during the rollover.
 */
public final class IngestKeyValidator {

  private final List<byte[]> keys;

  public IngestKeyValidator(List<String> keys) {
    this.keys = keys.stream()
      .map(k -> k.getBytes(StandardCharsets.UTF_8))
      .toList();
  }

  /** True when at least one key is configured and validation is enforced. */
  boolean enabled() {
    return !keys.isEmpty();
  }

  /**
   * Validate the provided {@code Insight-Key} header value, throwing HTTP 401
   * when validation is enabled and the value is missing or does not match.
   */
  public void validate(String provided) {
    if (enabled() && !matches(provided)) {
      throw new HttpResponseException(401, "Invalid or missing Insight-Key");
    }
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
