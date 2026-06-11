package org.ebean.monitor.cli;

/**
 * Minimal, TTY-gated ANSI colouring for chart glyphs.
 *
 * <p>Colour is only emitted when stdout is an interactive terminal so piped
 * output, redirected files and {@code -o json} stay plain. Honours the de-facto
 * {@code NO_COLOR} standard, and an {@code INSIGHT_COLOR=always|never} override.
 * Native-image safe (plain string concatenation, no third-party deps).
 */
final class AnsiColor {

  /** Cyan — a readable accent on both light and dark terminals. */
  private static final String CHART = "\u001b[36m";
  /** Bold yellow — actionable hotkey letters in interactive prompts. */
  private static final String KEY = "\u001b[1;33m";
  /** Bold magenta — a plan-shape change point that warrants attention. */
  private static final String CHANGE = "\u001b[1;35m";
  private static final String RESET = "\u001b[0m";

  private static final boolean ENABLED = computeEnabled();

  private AnsiColor() {
  }

  private static boolean computeEnabled() {
    String force = System.getenv("INSIGHT_COLOR");
    if (force != null) {
      return force.equalsIgnoreCase("always") || force.equalsIgnoreCase("yes") || force.equals("1");
    }
    if (System.getenv("NO_COLOR") != null) {
      return false;
    }
    return System.console() != null;
  }

  static boolean enabled() {
    return ENABLED;
  }

  /** Colour a chart glyph string (bars, columns, sparklines) when colour is enabled. */
  static String chart(String s) {
    return paint(ENABLED, CHART, s);
  }

  /** Colour an actionable hotkey letter (e.g. the {@code q} in {@code [q]uit}) when enabled. */
  static String key(String s) {
    return paint(ENABLED, KEY, s);
  }

  /** Colour a plan-shape change-point glyph/marker when colour is enabled. */
  static String change(String s) {
    return paint(ENABLED, CHANGE, s);
  }

  /** Render a bracketed hotkey option with the key letter accented, e.g. {@code hot("q","uit")} → {@code [q]uit}. */
  static String hot(String keyText, String rest) {
    return "[" + key(keyText) + "]" + rest;
  }

  /** Pure helper: wrap {@code s} in {@code code}…reset when {@code enabled}, else return it unchanged. */
  static String paint(boolean enabled, String code, String s) {
    return enabled ? code + s + RESET : s;
  }
}
