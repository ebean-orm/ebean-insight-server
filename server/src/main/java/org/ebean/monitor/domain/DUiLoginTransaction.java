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
@Table(name = "ebean_insight.ui_login_transaction")
public class DUiLoginTransaction extends Model {

  @Id
  @Length(43)
  private final String stateHash;

  @Version
  private long version;

  @Column(nullable = false)
  private String nonce;

  @Column(nullable = false)
  private String codeVerifier;

  @Column(nullable = false)
  private String returnPath;

  @Column(nullable = false)
  private Instant expiresAt;

  @Column
  private String previousSessionId;

  public DUiLoginTransaction(String stateHash) {
    this.stateHash = stateHash;
  }

  public String getStateHash() {
    return stateHash;
  }

  public String getNonce() {
    return nonce;
  }

  public void setNonce(String nonce) {
    this.nonce = nonce;
  }

  public String getCodeVerifier() {
    return codeVerifier;
  }

  public void setCodeVerifier(String codeVerifier) {
    this.codeVerifier = codeVerifier;
  }

  public String getReturnPath() {
    return returnPath;
  }

  public void setReturnPath(String returnPath) {
    this.returnPath = returnPath;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getPreviousSessionId() {
    return previousSessionId;
  }

  public void setPreviousSessionId(String previousSessionId) {
    this.previousSessionId = previousSessionId;
  }
}
