package org.ebean.monitor.domain;

import io.ebean.Model;
import io.ebean.annotation.Length;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "ebean_insight.ui_session")
public class DUiSession extends Model {

  @Id
  @Length(43)
  private final String sessionHash;

  @Version
  private long version;

  @Column(nullable = false)
  private String accessToken;

  @Column
  private String refreshToken;

  @Column
  private String idToken;

  @Column(nullable = false)
  private Instant accessTokenExpiresAt;

  @Column(nullable = false)
  private Instant expiresAt;

  public DUiSession(String sessionHash) {
    this.sessionHash = sessionHash;
  }

  public String getSessionHash() {
    return sessionHash;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public String getRefreshToken() {
    return refreshToken;
  }

  public void setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken;
  }

  public String getIdToken() {
    return idToken;
  }

  public void setIdToken(String idToken) {
    this.idToken = idToken;
  }

  public Instant getAccessTokenExpiresAt() {
    return accessTokenExpiresAt;
  }

  public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) {
    this.accessTokenExpiresAt = accessTokenExpiresAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
