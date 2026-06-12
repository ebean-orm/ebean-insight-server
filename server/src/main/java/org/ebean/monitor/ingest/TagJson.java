package org.ebean.monitor.ingest;

import java.util.Map;

/**
 * Renders an ordered tag map as a flat JSON object string for persistence.
 * <p>
 * The ingest input is the canonical wire tag string ({@code "kind:orm,label:X"}),
 * not JSON, so a small transform is needed here. Tags are persisted in a {@code jsonb}
 * column (so {@code tags ->> 'label'} grouping works in SQL) but written as a raw JSON
 * String via Ebean's {@code ScalarTypeJsonString} - avoiding a Jackson dependency on the
 * otherwise avaje-jsonb based server. Reading back to a map uses avaje-jsonb's built-in
 * {@code Map<String,String>} adapter (see V1QueryService).
 */
final class TagJson {

  private TagJson() {
  }

  /**
   * Render an ordered tag map as a flat JSON object string, or null when empty.
   */
  static String toJson(Map<String, Object> tags) {
    if (tags == null || tags.isEmpty()) {
      return null;
    }
    final StringBuilder sb = new StringBuilder(64);
    sb.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeString(sb, entry.getKey());
      sb.append(':');
      final Object value = entry.getValue();
      writeString(sb, value == null ? "" : value.toString());
    }
    sb.append('}');
    return sb.toString();
  }

  private static void writeString(StringBuilder sb, String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
