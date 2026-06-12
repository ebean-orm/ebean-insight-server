package org.ebean.monitor.forward;

import java.util.ArrayList;

/**
 * Translates ebean-flat metric names (e.g. {@code iud.User.save}, {@code orm.User.find},
 * {@code txn.named.X}, {@code l2.<region>.<op>}) into OTel-friendly
 * {@code (name, attributes)} pairs using the label-tag convention.
 *
 * <p>Mirrors {@code io.avaje.metrics.ebean.EbeanMetricNaming} in
 * {@code avaje-metrics-ebean} but is self-contained to avoid the transitive
 * {@code ebean-datasource-api} dependency on the server side.
 *
 * <p>Non-ebean names (e.g. {@code jvm.*}, {@code app.*}) are passed through
 * with no extra attributes.
 */
final class MetricNameMapper {

  /** Result of a name translation: OTel metric name plus key/value attributes. */
  record Mapped(String name, String[] attrs) {
    static final String[] EMPTY = new String[0];
  }

  private MetricNameMapper() {
  }

  static Mapped map(String ebeanName) {
    if (ebeanName == null || ebeanName.isEmpty()) {
      return new Mapped("ebean.other", Mapped.EMPTY);
    }
    int firstDot = ebeanName.indexOf('.');
    if (firstDot <= 0) {
      return passthrough(ebeanName);
    }
    var prefix = ebeanName.substring(0, firstDot);
    var rest = ebeanName.substring(firstDot + 1);
    return switch (prefix) {
      case "iud" -> new Mapped("ebean.dml", new String[]{"label", rest});
      case "dto" -> new Mapped("ebean.query", new String[]{"type", "dto", "label", rest});
      case "orm" -> new Mapped("ebean.query", new String[]{"type", "orm", "label", rest});
      case "sql" -> new Mapped("ebean.query", new String[]{"type", "sql", "label", rest});
      case "txn" -> {
        var label = rest.startsWith("named.") ? rest.substring("named.".length()) : rest;
        yield new Mapped("ebean.txn", new String[]{"label", label});
      }
      case "l2" -> l2(rest);
      default -> passthrough(ebeanName);
    };
  }

  /**
   * Build a {@code Mapped} for a v2 metric where the family {@code name} is already
   * canonical and {@code tags} is the canonical {@code "key:value,key2:value2"} string.
   * The tag pairs become OTel attributes verbatim (no re-derivation from the name).
   */
  static Mapped fromV2(String family, String tags) {
    if (tags == null || tags.isEmpty()) {
      return new Mapped(family, Mapped.EMPTY);
    }
    var pairs = tags.split(",");
    var attrs = new ArrayList<String>(pairs.length * 2);
    for (var pair : pairs) {
      if (pair.isEmpty()) {
        continue;
      }
      int colon = pair.indexOf(':');
      if (colon <= 0) {
        attrs.add(pair);
        attrs.add("");
      } else {
        attrs.add(pair.substring(0, colon));
        attrs.add(pair.substring(colon + 1));
      }
    }
    return new Mapped(family, attrs.toArray(new String[0]));
  }

  private static Mapped passthrough(String name) {
    return new Mapped(name, Mapped.EMPTY);
  }

  private static Mapped l2(String rest) {
    int dot = rest.indexOf('.');
    if (dot <= 0) {
      return new Mapped("ebean.l2", new String[]{"op", rest});
    }
    var region = rest.substring(0, dot);
    var op = rest.substring(dot + 1);
    return new Mapped("ebean.l2", new String[]{"op", op, "region", region});
  }
}
