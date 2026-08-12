package org.ebean.monitor.ui;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryUiSessionStore implements UiSessionStore {
  private final ConcurrentHashMap<String, UiSession> sessions = new ConcurrentHashMap<>();

  @Override
  public void save(UiSession session) {
    sessions.put(session.id(), session);
  }

  @Override
  public Optional<UiSession> find(String id) {
    UiSession session = sessions.get(id);
    if (session != null && session.expiresAt().isAfter(Instant.now())) {
      return Optional.of(session);
    }
    if (session != null) sessions.remove(id, session);
    return Optional.empty();
  }

  @Override
  public void delete(String id) {
    sessions.remove(id);
  }
}
