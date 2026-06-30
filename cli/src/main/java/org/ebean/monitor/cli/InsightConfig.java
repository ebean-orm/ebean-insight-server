package org.ebean.monitor.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Persisted CLI defaults stored in {@code ~/.insight/config.properties}.
 *
 * <p>Keeps deployment-specific settings (namespace, service, …) out of the binary:
 * users set them once with {@code insight config set <key> <value>} and every
 * command then picks them up unless overridden by an explicit flag.
 *
 * <h3>Profiles</h3>
 * Named profiles are stored as {@code ~/.insight/profiles/<name>.properties}.
 * When a profile is active (tracked via {@code active-profile} in the base
 * config), its properties are merged over the base on every {@link #load()},
 * letting users switch targets with {@code insight config use <name>}.
 * Per-profile tokens are stored as {@code ~/.insight/token-<name>.json}.
 */
final class InsightConfig {

  /** Settings that may be persisted; these mirror the connection option names. */
  static final List<String> KEYS = List.of(
      "url", "namespace", "service", "target-port", "local-port",
      "context", "ready-timeout", "output", "env", "app",
      "auth-domain", "auth-user-pool-id", "auth-client-id", "auth-scope",
      "auth-redirect-port");

  /** Internal key that tracks the active profile — not exposed via {@link #KEYS}. */
  static final String ACTIVE_PROFILE_KEY = "active-profile";

  private final Path file;

  InsightConfig() {
    this(defaultFile());
  }

  InsightConfig(Path file) {
    this.file = file;
  }

  static Path defaultFile() {
    String home = System.getProperty("user.home", ".");
    return Path.of(home, ".insight", "config.properties");
  }

  /** Directory where named profile files are stored (relative to this config file). */
  Path instanceProfilesDir() {
    return file.getParent().resolve("profiles");
  }

  /** Path for a named profile's properties file (relative to this config file). */
  Path instanceProfileFile(String name) {
    return instanceProfilesDir().resolve(name + ".properties");
  }

  /** Directory where named profile files are stored (for the default config location). */
  static Path profilesDir() {
    return defaultFile().getParent().resolve("profiles");
  }

  /** Path for a named profile's properties file (for the default config location). */
  static Path profileFile(String name) {
    return profilesDir().resolve(name + ".properties");
  }

  Path file() {
    return file;
  }

  /** The active profile name, or {@code null} when none is set. */
  @Nullable String activeProfile() {
    String v = loadBase().getProperty(ACTIVE_PROFILE_KEY);
    return (v == null || v.isBlank()) ? null : v.trim();
  }

  /** Activate a named profile (writes {@code active-profile} to the base config). */
  void useProfile(String name) {
    var props = loadBase();
    props.setProperty(ACTIVE_PROFILE_KEY, name);
    storeFile(file, props);
  }

  /** Deactivate any active profile (removes {@code active-profile} from the base config). */
  void clearProfile() {
    var props = loadBase();
    if (props.remove(ACTIVE_PROFILE_KEY) != null) {
      storeFile(file, props);
    }
  }

  /** Returns the sorted names of all available profiles. */
  List<String> listProfiles() {
    Path dir = instanceProfilesDir();
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".properties"))
          .map(n -> n.substring(0, n.length() - ".properties".length()))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed listing profiles in " + dir, e);
    }
  }

  /** Load ONLY the base config (no profile merging). */
  Properties loadBase() {
    return loadFile(file);
  }

  /**
   * Load the effective config: base properties with the active profile's
   * properties merged on top (profile wins on collision).
   */
  Properties load() {
    return load(null);
  }

  /**
   * Load the effective config, using {@code explicitProfile} when not null,
   * otherwise falling back to the {@code active-profile} stored in the base config.
   */
  Properties load(@Nullable String explicitProfile) {
    Properties base = loadBase();
    String profileName = (explicitProfile != null && !explicitProfile.isBlank())
        ? explicitProfile.trim()
        : base.getProperty(ACTIVE_PROFILE_KEY);
    if (profileName != null && !profileName.isBlank()) {
      Path profilePath = instanceProfileFile(profileName.trim());
      if (Files.exists(profilePath)) {
        loadFile(profilePath).forEach((k, v) -> base.setProperty((String) k, (String) v));
      }
    }
    return base;
  }

  /** Returns the effective value for {@code key} (merged config). */
  @Nullable String get(String key) {
    requireKnown(key);
    return load().getProperty(key);
  }

  /** Write {@code key=value} to the base config. */
  void set(String key, String value) {
    requireKnown(key);
    var props = loadBase();
    props.setProperty(key, value);
    storeFile(file, props);
  }

  /** Write {@code key=value} to a named profile (creating the file if needed). */
  void setInProfile(String profileName, String key, String value) {
    requireKnown(key);
    Path pf = instanceProfileFile(profileName);
    var props = loadFile(pf);
    props.setProperty(key, value);
    storeFile(pf, props);
  }

  /** Remove {@code key} from the base config. Returns {@code false} when it was not set. */
  boolean unset(String key) {
    requireKnown(key);
    var props = loadBase();
    if (props.remove(key) == null) {
      return false;
    }
    storeFile(file, props);
    return true;
  }

  /** Remove {@code key} from a named profile. Returns {@code false} when not found. */
  boolean unsetInProfile(String profileName, String key) {
    requireKnown(key);
    Path pf = instanceProfileFile(profileName);
    if (!Files.exists(pf)) {
      return false;
    }
    var props = loadFile(pf);
    if (props.remove(key) == null) {
      return false;
    }
    storeFile(pf, props);
    return true;
  }

  /** All effective (merged) settings, sorted by key. Excludes internal keys. */
  Map<String, String> all() {
    var props = load();
    var map = new TreeMap<String, String>();
    for (String name : props.stringPropertyNames()) {
      if (!ACTIVE_PROFILE_KEY.equals(name)) {
        map.put(name, props.getProperty(name));
      }
    }
    return map;
  }

  static boolean isKnown(String key) {
    return KEYS.contains(key);
  }

  private static void requireKnown(String key) {
    if (!isKnown(key)) {
      throw new CliException("Unknown config key '" + key + "'. Known keys: " + String.join(", ", KEYS));
    }
  }

  private static Properties loadFile(Path path) {
    var props = new Properties();
    if (Files.exists(path)) {
      try (var in = Files.newInputStream(path)) {
        props.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed reading " + path, e);
      }
    }
    return props;
  }

  private static void storeFile(Path path, Properties props) {
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (var out = Files.newOutputStream(path)) {
        props.store(out, "ebean-insight CLI config");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing " + path, e);
    }
  }
}
