package org.ebean.monitor.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * Persisted CLI defaults stored in {@code ~/.insight/config.properties}.
 *
 * <p>Keeps deployment-specific settings (namespace, service, …) out of the binary:
 * users set them once with {@code insight config set <key> <value>} and every
 * command then picks them up unless overridden by an explicit flag.
 */
final class InsightConfig {

  /** Settings that may be persisted; these mirror the connection option names. */
  static final List<String> KEYS = List.of(
      "url", "namespace", "service", "target-port", "local-port",
      "context", "ready-timeout", "insight-key", "output");

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

  Path file() {
    return file;
  }

  Properties load() {
    var props = new Properties();
    if (Files.exists(file)) {
      try (var in = Files.newInputStream(file)) {
        props.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed reading " + file, e);
      }
    }
    return props;
  }

  @Nullable String get(String key) {
    requireKnown(key);
    return load().getProperty(key);
  }

  void set(String key, String value) {
    requireKnown(key);
    var props = load();
    props.setProperty(key, value);
    store(props);
  }

  boolean unset(String key) {
    requireKnown(key);
    var props = load();
    if (props.remove(key) == null) {
      return false;
    }
    store(props);
    return true;
  }

  /** All persisted settings, sorted by key. */
  Map<String, String> all() {
    var props = load();
    var map = new TreeMap<String, String>();
    for (String name : props.stringPropertyNames()) {
      map.put(name, props.getProperty(name));
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

  private void store(Properties props) {
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (var out = Files.newOutputStream(file)) {
        props.store(out, "ebean-insight CLI config");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing " + file, e);
    }
  }
}
