package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiColorTest {

  @Test
  void paint_whenEnabled_wrapsInCodeAndReset() {
    String out = AnsiColor.paint(true, "\u001b[36m", "\u2588\u2588");
    assertThat(out).isEqualTo("\u001b[36m\u2588\u2588\u001b[0m");
  }

  @Test
  void paint_whenDisabled_returnsUnchanged() {
    String s = "\u2588\u2588";
    assertThat(AnsiColor.paint(false, "\u001b[36m", s)).isEqualTo(s);
  }

  @Test
  void chart_consistentWithEnabledFlag() {
    String s = "\u2581\u2588\u2581";
    if (AnsiColor.enabled()) {
      assertThat(AnsiColor.chart(s)).contains(s).startsWith("\u001b[");
    } else {
      // non-tty (typical test/CI run): chart() is a no-op so piped output stays clean
      assertThat(AnsiColor.chart(s)).isEqualTo(s);
    }
  }

  @Test
  void hot_keepsBracketsAndLabel_keyOnlyAccented() {
    // brackets and the trailing label are never coloured; only the key letter is.
    if (AnsiColor.enabled()) {
      assertThat(AnsiColor.hot("q", "uit")).startsWith("[").endsWith("uit").contains("q");
    } else {
      assertThat(AnsiColor.hot("q", "uit")).isEqualTo("[q]uit");
      assertThat(AnsiColor.hot("1-6", "")).isEqualTo("[1-6]");
    }
  }
}
