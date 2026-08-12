package org.ebean.monitor.ui;

import io.avaje.config.Config;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class DatabaseUiStoreTest {
  private static final UiTokenCodec CODEC;

  static {
    Config.setProperty("insight.ui.auth.token-encryption-key",
      "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    CODEC = UiTokenCodec.load();
  }

  @Inject
  static Database database;

  @Test
  void sessionRoundTripAndExpiredCleanup() {
    DatabaseUiSessionStore store = new DatabaseUiSessionStore(database, CODEC);
    Instant now = Instant.now();
    String id = "session-" + now.toEpochMilli();
    store.save(new UiSession(id, "access", "refresh", "id", now.plusSeconds(300), now.plusSeconds(3600)));

    assertThat(store.find(id)).contains(new UiSession(
      id, "access", "refresh", "id", now.plusSeconds(300), now.plusSeconds(3600)));

    String expiredId = id + "-expired";
    store.save(new UiSession(expiredId, "old-access", null, null,
      now.minusSeconds(120), now.minusSeconds(60)));
    assertThat(store.find(expiredId)).isEmpty();
    assertThat(store.cleanupExpired()).isGreaterThanOrEqualTo(1);
    assertThat(database.find(org.ebean.monitor.domain.DUiSession.class,
      UiTokenCodec.hash(expiredId))).isNull();
  }

  @Test
  void loginTransactionIsConsumedOnceAndExpiredRowsCleaned() {
    DatabaseUiLoginTransactionStore store = new DatabaseUiLoginTransactionStore(database, CODEC);
    Instant now = Instant.now();
    String state = "state-" + now.toEpochMilli();
    store.save(new UiLoginTransaction(state, "nonce", "verifier", "/ux",
      now.plusSeconds(300), "previous-session"));

    assertThat(store.consume(state)).contains(new UiLoginTransaction(
      state, "nonce", "verifier", "/ux", now.plusSeconds(300), "previous-session"));
    assertThat(store.consume(state)).isEmpty();

    String expiredState = state + "-expired";
    store.save(new UiLoginTransaction(expiredState, "nonce", "verifier", "/ux",
      now.minusSeconds(60), null));
    assertThat(store.cleanupExpired()).isGreaterThanOrEqualTo(1);
    assertThat(database.find(org.ebean.monitor.domain.DUiLoginTransaction.class,
      UiTokenCodec.hash(expiredState))).isNull();
  }
}
