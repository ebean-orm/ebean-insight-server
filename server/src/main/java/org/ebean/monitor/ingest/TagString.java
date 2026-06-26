package org.ebean.monitor.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the canonical v2 tag string ({@code "key:value,key2:value2"}) into an
 * ordered map. The string is produced sorted by the client/source serializers,
 * so insertion order is preserved here.
 */
final class TagString {

  private TagString() {
  }

  /**
   * Parse the canonical tag string into an ordered map, or null when blank.
   */
  static Map<String, String> parse(String tags) {
    if (tags == null || tags.isEmpty()) {
      return null;
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (String pair : tags.split(",")) {
      if (pair.isEmpty()) {
        continue;
      }
      int colon = pair.indexOf(':');
      if (colon <= 0) {
        map.put(pair, "");
      } else {
        map.put(pair.substring(0, colon), pair.substring(colon + 1));
      }
    }
    return map.isEmpty() ? null : map;
  }
}
