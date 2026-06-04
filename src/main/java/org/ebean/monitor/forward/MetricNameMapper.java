package org.ebean.monitor.forward;

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
