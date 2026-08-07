package org.ebean.monitor.web;

/**
 * Fixed categorical color palette shared by every dashboard chart (Chart.js
 * and server-rendered) so a given series/hash renders the same color
 * wherever it appears - e.g. the same hash swatch color on the
 * {@code /ux/metric-detail} hash-breakdown table and its matching rows in
 * the recently-captured-plans table.
 */
final class Palette {

  static final String[] COLORS = {
    "#4e79a7", "#f28e2b", "#e15759", "#76b7b2",
    "#59a14f", "#edc949", "#af7aa1", "#ff9da7"
  };

  /** Neutral grey used for the synthetic "Other" series / unranked rows. */
  static final String OTHER_COLOR = "#b0b0b0";

  private Palette() {
  }

  static String colorFor(int index) {
    return COLORS[index % COLORS.length];
  }
}
