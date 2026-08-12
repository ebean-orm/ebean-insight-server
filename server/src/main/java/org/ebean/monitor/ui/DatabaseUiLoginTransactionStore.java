package org.ebean.monitor.ui;

import io.ebean.Database;
import io.ebean.Transaction;
import org.ebean.monitor.domain.DUiLoginTransaction;

import java.time.Instant;
import java.util.Optional;

final class DatabaseUiLoginTransactionStore implements UiLoginTransactionStore {
  private final Database database;
  private final UiTokenCodec codec;

  DatabaseUiLoginTransactionStore(Database database, UiTokenCodec codec) {
    this.database = database;
    this.codec = codec;
  }

  @Override
  public void save(UiLoginTransaction transaction) {
    String hash = UiTokenCodec.hash(transaction.state());
    DUiLoginTransaction bean = new DUiLoginTransaction(hash);
    bean.setNonce(codec.encrypt(transaction.nonce()));
    bean.setCodeVerifier(codec.encrypt(transaction.codeVerifier()));
    bean.setReturnPath(transaction.returnPath());
    bean.setExpiresAt(transaction.expiresAt());
    bean.setPreviousSessionId(codec.encrypt(transaction.previousSessionId()));
    database.insert(bean);
  }

  @Override
  public Optional<UiLoginTransaction> consume(String state) {
    try (Transaction transaction = database.beginTransaction()) {
      DUiLoginTransaction bean = database.find(DUiLoginTransaction.class)
        .where()
        .eq("stateHash", UiTokenCodec.hash(state))
        .forUpdate()
        .findOne();
      if (bean == null || !bean.getExpiresAt().isAfter(Instant.now())) {
        transaction.commit();
        return Optional.empty();
      }
      database.delete(bean, transaction);
      transaction.commit();
      return Optional.of(new UiLoginTransaction(
        state,
        codec.decrypt(bean.getNonce()),
        codec.decrypt(bean.getCodeVerifier()),
        bean.getReturnPath(),
        bean.getExpiresAt(),
        codec.decrypt(bean.getPreviousSessionId())));
    }
  }

  @Override
  public int cleanupExpired() {
    return database.createQuery(DUiLoginTransaction.class)
      .where()
      .lt("expiresAt", Instant.now())
      .delete();
  }
}
