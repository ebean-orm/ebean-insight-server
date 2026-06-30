package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Remove the cached OAuth2 tokens ({@code ~/.insight/token.json}). */
@Command(name = "logout", mixinStandardHelpOptions = true,
    description = "Remove the cached bearer token (~/.insight/token.json).")
final class LogoutCommand implements Callable<Integer> {

  @Option(names = "--profile", paramLabel = "NAME",
      description = "Log out of a named profile instead of the active one.")
  @Nullable String profile;

  @Override
  public Integer call() {
    TokenStore store = TokenStore.forProfile(profile);
    boolean removed = store.clear();
    System.out.println(removed
        ? "Logged out. Removed " + store.file()
        : "Not logged in (no cached token).");
    return 0;
  }
}
