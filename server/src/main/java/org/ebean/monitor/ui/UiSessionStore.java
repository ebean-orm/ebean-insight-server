package org.ebean.monitor.ui;

import java.util.Optional;

interface UiSessionStore {
  void save(UiSession session);
  Optional<UiSession> find(String id);
  void delete(String id);
}
