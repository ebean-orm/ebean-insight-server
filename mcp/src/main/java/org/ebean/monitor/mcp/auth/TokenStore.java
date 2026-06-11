package org.ebean.monitor.mcp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the inbound MCP client bearer tokens parsed from the {@code mcp.tokens}
 * configuration and resolves a presented token to its principal name.
 * <p>
 * Configuration format is a comma separated list of {@code name:value} entries
 * (the name is a human-readable label used for audit logging; the value is the
 * shared secret), for example:
 * <pre>{@code mcp.tokens=claude-desktop:abc123,cli-agent:def456}</pre>
 * An entry without a colon is accepted with the label {@code "unnamed"}.
 * Multiple tokens allow rotation — old and new both validate during rollover.
 * <p>
 * When no tokens are configured the store is <em>disabled</em>: {@link #enabled()}
 * returns false and the auth filter leaves the server open (protected only by
 * network isolation), mirroring the server's ingest-key behaviour.
 */
public final class TokenStore {

  private record Token(String name, byte[] value) {}

  private final List<Token> tokens;

  public TokenStore(String raw) {
    this.tokens = parse(raw);
  }

  /** True when at least one token is configured and auth is enforced. */
  public boolean enabled() {
    return !tokens.isEmpty();
  }

  /**
   * Return the principal name (token label) when the presented token matches a
   * configured token (constant-time compare), otherwise {@code null}.
   */
  public String principalFor(String presented) {
    if (presented == null || presented.isEmpty()) {
      return null;
    }
    byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
    String match = null;
    for (Token token : tokens) {
      if (MessageDigest.isEqual(presentedBytes, token.value())) {
        match = token.name();
      }
    }
    return match;
  }

  private static List<Token> parse(String raw) {
    List<Token> result = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return result;
    }
    for (String entry : raw.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int colon = trimmed.indexOf(':');
      String name;
      String value;
      if (colon < 0) {
        name = "unnamed";
        value = trimmed;
      } else {
        name = trimmed.substring(0, colon).trim();
        value = trimmed.substring(colon + 1).trim();
      }
      if (!value.isEmpty()) {
        result.add(new Token(name, value.getBytes(StandardCharsets.UTF_8)));
      }
    }
    return result;
  }
}
