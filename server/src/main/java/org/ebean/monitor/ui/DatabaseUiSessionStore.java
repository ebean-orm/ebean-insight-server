package org.ebean.monitor.ui;

import io.ebean.Database;
import org.ebean.monitor.domain.DUiSession;

import java.time.Instant;
import java.util.Optional;

final class DatabaseUiSessionStore implements UiSessionStore {

  private final Database database;
  private final UiTokenCodec codec;

  DatabaseUiSessionStore(Database database, UiTokenCodec codec) {
    this.database = database;
    this.codec = codec;
  }

  @Override
  public void save(UiSession session) {
    String hash = UiTokenCodec.hash(session.id());
    DUiSession bean = database.find(DUiSession.class, hash);
    if (bean == null) {
      bean = new DUiSession(hash);
    }
    bean.setAccessToken(codec.encrypt(session.accessToken()));
    bean.setRefreshToken(codec.encrypt(session.refreshToken()));
    bean.setIdToken(codec.encrypt(session.idToken()));
    bean.setUserSub(codec.encrypt(session.userSub()));
    bean.setAccessTokenExpiresAt(session.accessTokenExpiresAt());
    bean.setExpiresAt(session.expiresAt());
    database.save(bean);
  }

  @Override
  public Optional<UiSession> find(String id) {
    DUiSession bean = database.find(DUiSession.class, UiTokenCodec.hash(id));
    if (bean == null || !bean.getExpiresAt().isAfter(Instant.now())) {
      return Optional.empty();
    }
    return Optional.of(new UiSession(
      id,
      codec.decrypt(bean.getAccessToken()),
      codec.decrypt(bean.getRefreshToken()),
      codec.decrypt(bean.getIdToken()),
      bean.getAccessTokenExpiresAt(),
      bean.getExpiresAt(),
      codec.decrypt(bean.getUserSub())));
  }

  @Override
  public void delete(String id) {
    DUiSession bean = database.find(DUiSession.class, UiTokenCodec.hash(id));
    if (bean != null) {
      database.delete(bean);
    }
  }

  @Override
  public int cleanupExpired() {
    return database.createQuery(DUiSession.class)
      .where()
      .lt("expiresAt", Instant.now())
      .delete();
  }
}
