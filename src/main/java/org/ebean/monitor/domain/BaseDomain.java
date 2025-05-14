package org.ebean.monitor.domain;

import io.ebean.Model;
import io.ebean.annotation.WhenCreated;
import io.ebean.annotation.WhenModified;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;

@MappedSuperclass
public class BaseDomain extends Model {

  @Id
  private int id;

  @Version
  private int version;

  @WhenCreated
  private Instant whenCreated;

  @WhenModified
  private Instant whenModified;

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public Instant getWhenCreated() {
    return whenCreated;
  }

  public Instant getWhenModified() {
    return whenModified;
  }
}
