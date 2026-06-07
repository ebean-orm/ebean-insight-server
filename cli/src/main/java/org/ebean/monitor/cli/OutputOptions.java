package org.ebean.monitor.cli;

import java.util.List;

import io.avaje.jsonb.Jsonb;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * Shared output-format option. Commands render either the default human-readable
 * plain text or, with {@code -o json}, compact JSON suitable for piping to tools
 * such as {@code jq}.
 *
 * <p>When the flag is omitted the format falls back to the persisted
 * {@code output} setting in {@code ~/.insight/config.properties}, then to
 * {@code text}.
 */
final class OutputOptions {

  enum Format { text, json }

  @Option(names = {"-o", "--output"},
      description = "Output format: ${COMPLETION-CANDIDATES}. Defaults to the persisted "
          + "'output' config setting, or 'text'.")
  @Nullable Format format;

  private @Nullable Jsonb jsonb;
  private boolean resolved;

  /** Fill the format from {@code ~/.insight/config.properties} when no flag was given. */
  void resolve() {
    resolve(new InsightConfig());
  }

  void resolve(InsightConfig config) {
    if (format == null) {
      format = parseFormat(config.load().getProperty("output"));
    }
    resolved = true;
  }

  private static Format parseFormat(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return Format.text;
    }
    try {
      return Format.valueOf(value.trim());
    } catch (IllegalArgumentException e) {
      throw new CliException("Invalid output format '" + value
          + "' in config. Valid values: text, json");
    }
  }

  boolean json() {
    if (!resolved) {
      resolve();
    }
    return format == Format.json;
  }

  private Jsonb jsonb() {
    if (jsonb == null) {
      jsonb = Jsonb.builder().build();
    }
    return jsonb;
  }

  /** Print a single value as JSON. */
  <T> void printJson(Class<T> type, T value) {
    System.out.println(jsonb().type(type).toJson(value));
  }

  /** Print a list of values as a JSON array. */
  <T> void printJsonList(Class<T> type, List<T> values) {
    System.out.println(jsonb().type(type).list().toJson(values));
  }
}
