package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

/** Remove the cached OAuth2 tokens ({@code ~/.insight/token.json}). */
@Command(name = "logout", mixinStandardHelpOptions = true,
    description = "Remove the cached bearer token (~/.insight/token.json).")
final class LogoutCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    TokenStore store = new TokenStore();
    boolean removed = store.clear();
    System.out.println(removed
        ? "Logged out. Removed " + store.file()
        : "Not logged in (no cached token).");
    return 0;
  }
}
