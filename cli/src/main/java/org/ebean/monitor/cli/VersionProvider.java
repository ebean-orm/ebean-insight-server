package org.ebean.monitor.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Supplies the version banner shown by {@code insight --version}.
 *
 * <p>Reads {@code ebean-insight-cli.properties} from the classpath; the file is
 * populated at build time via Maven resource filtering and the git-commit-id
 * plugin. When run from the IDE without a Maven build the placeholders are
 * left intact, in which case we fall back to {@code "unknown"} so the command
 * still produces sensible output.
 */
final class VersionProvider implements IVersionProvider {

  static final String RESOURCE = "ebean-insight-cli.properties";

  @Override
  public String[] getVersion() {
    Properties props = load();
    String version = value(props, "version");
    String commit = value(props, "commit");
    String buildTime = value(props, "buildTime");
    return new String[] {
        "ebean-insight-cli " + version,
        "commit: " + commit,
        "built: " + buildTime,
    };
  }

  private static Properties load() {
    Properties props = new Properties();
    try (InputStream in = VersionProvider.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException ignore) {
      // fall through to defaults
    }
    return props;
  }

  private static String value(Properties props, String key) {
    String raw = props.getProperty(key);
    if (raw == null || raw.isBlank() || raw.startsWith("${")) {
      return "unknown";
    }
    return raw;
  }
}
