package org.ebean.monitor.cli;

import java.util.List;

import io.avaje.jsonb.Jsonb;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * Shared output-format option. Commands render either the default human-readable
 * plain text or, with {@code -o json}, pretty-printed JSON suitable for piping to
 * tools such as {@code jq}.
 */
final class OutputOptions {

  enum Format { text, json }

  @Option(names = {"-o", "--output"}, defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
  Format format = Format.text;

  private @Nullable Jsonb jsonb;

  boolean json() {
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
