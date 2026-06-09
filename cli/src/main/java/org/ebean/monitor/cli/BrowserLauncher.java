package org.ebean.monitor.cli;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Opens the system web browser at a URL using the platform launcher
 * ({@code open} / {@code xdg-open} / {@code rundll32}).
 *
 * <p>Deliberately avoids {@code java.awt.Desktop} so the CLI works as a GraalVM
 * native image (no AWT). When no launcher succeeds the caller should print the
 * URL for the user to open manually.
 */
final class BrowserLauncher {

  private BrowserLauncher() {
  }

  /** Attempt to open {@code url} in the default browser; true when a launcher started. */
  static boolean open(String url) {
    for (List<String> command : commandsFor(url)) {
      try {
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        return true;
      } catch (IOException ignored) {
        // try the next candidate launcher
      }
    }
    return false;
  }

  private static List<List<String>> commandsFor(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      return List.of(List.of("open", url));
    }
    if (os.contains("win")) {
      return List.of(List.of("rundll32", "url.dll,FileProtocolHandler", url));
    }
    return List.of(List.of("xdg-open", url), List.of("gio", "open", url));
  }
}
