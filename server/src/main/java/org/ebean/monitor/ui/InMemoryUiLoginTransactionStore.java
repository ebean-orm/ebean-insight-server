package org.ebean.monitor.ui;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryUiLoginTransactionStore implements UiLoginTransactionStore {
  private final ConcurrentHashMap<String, UiLoginTransaction> transactions = new ConcurrentHashMap<>();

  @Override
  public void save(UiLoginTransaction transaction) {
    transactions.put(transaction.state(), transaction);
  }

  @Override
  public Optional<UiLoginTransaction> consume(String state) {
    UiLoginTransaction transaction = transactions.remove(state);
    return transaction != null && transaction.expiresAt().isAfter(Instant.now())
      ? Optional.of(transaction) : Optional.empty();
  }
}
