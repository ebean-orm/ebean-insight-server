package org.ebean.monitor.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the cached OAuth2 tokens in {@code ~/.insight/token.json}.
 *
 * <p>The file is created with owner-only permissions ({@code 0600}) on POSIX
 * filesystems so the bearer token is not world-readable.
 */
final class TokenStore {

  private final Path file;
  private final JsonType<TokenData> type;

  TokenStore() {
    this(defaultFile());
  }

  TokenStore(Path file) {
    this.file = file;
    this.type = Jsonb.builder().build().type(TokenData.class);
  }

  /** Token store for the currently active profile, or the default when none is active. */
  static TokenStore forActiveProfile() {
    String profile = new InsightConfig().activeProfile();
    return new TokenStore(tokenFile(profile));
  }

  static Path defaultFile() {
    return tokenFile(null);
  }

  /** Token file path for the given profile name (null = default). */
  static Path tokenFile(@Nullable String profileName) {
    String home = System.getProperty("user.home", ".");
    if (profileName == null || profileName.isBlank()) {
      return Path.of(home, ".insight", "token.json");
    }
    return Path.of(home, ".insight", "token-" + profileName + ".json");
  }

  Path file() {
    return file;
  }

  Optional<TokenData> load() {
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try (var in = Files.newInputStream(file)) {
      return Optional.of(type.fromJson(in));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading " + file, e);
    }
  }

  void save(TokenData token) {
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      byte[] content = type.toJsonBytes(token);
      Files.write(file, content);
      restrictPermissions();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing " + file, e);
    }
  }

  /** Delete the cached token, returning true when a file was removed. */
  boolean clear() {
    try {
      return Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed deleting " + file, e);
    }
  }

  private void restrictPermissions() {
    try {
      Set<PosixFilePermission> perms = EnumSet.of(
          PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(file, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // non-POSIX filesystem (e.g. Windows) — best effort only
    }
  }
}
