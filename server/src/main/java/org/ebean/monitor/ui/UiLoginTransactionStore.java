package org.ebean.monitor.ui;

import java.util.Optional;

interface UiLoginTransactionStore {
  void save(UiLoginTransaction transaction);
  Optional<UiLoginTransaction> consume(String state);
  default int cleanupExpired() {
    return 0;
  }
}
