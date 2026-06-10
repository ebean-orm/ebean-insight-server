package org.ebean.monitor.cli;

import java.util.List;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * Result of a single plan-capture request, used for {@code insight capture}
 * JSON output when capturing one or more hashes.
 */
@Json
record CaptureResult(String hash, @Nullable String label, @Nullable String error) {

  /**
   * Render the capture results as a text table (header + dynamic-width HASH and
   * LABEL columns). Shared by {@code capture} and {@code missing-plans --capture}
   * so both render identically and match the {@code pending} table style.
   */
  static void printText(List<CaptureResult> results) {
    int hashWidth = "HASH".length();
    int labelWidth = "LABEL".length();
    for (CaptureResult r : results) {
      hashWidth = Math.max(hashWidth, r.hash().length());
      if (r.label() != null) {
        labelWidth = Math.max(labelWidth, r.label().length());
      }
    }
    String fmt = "%-" + hashWidth + "s  %-" + labelWidth + "s  %s%n";
    System.out.printf(fmt, "HASH", "LABEL", "STATUS");
    for (CaptureResult r : results) {
      System.out.printf(fmt, r.hash(), r.label() == null ? "" : r.label(), r.status());
    }
  }

  private String status() {
    return error != null ? "FAILED (" + error + ")" : "requested";
  }
}
