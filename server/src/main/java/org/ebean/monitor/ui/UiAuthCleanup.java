package org.ebean.monitor.ui;

import io.avaje.inject.PostConstruct;
import io.ebean.DB;
import org.ebean.monitor.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

@Singleton
final class UiAuthCleanup {
  private static final Logger log = LoggerFactory.getLogger(UiAuthCleanup.class);

  private final UiSessionStore sessions;
  private final UiLoginTransactionStore transactions;

  UiAuthCleanup(UiSessionStore sessions, UiLoginTransactionStore transactions) {
    this.sessions = sessions;
    this.transactions = transactions;
  }

  @PostConstruct
  void start() {
    if (Application.isForwardOnly()) {
      return;
    }
    cleanup();
    DB.backgroundExecutor().scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.DAYS);
  }

  private void cleanup() {
    int sessionsDeleted = sessions.cleanupExpired();
    int transactionsDeleted = transactions.cleanupExpired();
    if (sessionsDeleted > 0 || transactionsDeleted > 0) {
      log.info("deleted {} expired UI sessions and {} expired UI login transactions",
        sessionsDeleted, transactionsDeleted);
    }
  }
}
