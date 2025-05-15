package org.ebean.monitor.domain;

import io.ebean.Model;
import io.ebean.annotation.Length;
import io.ebean.annotation.NotNull;
import org.ebean.monitor.domain.finder.DJobFinder;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "ebean_insight.job")
public class DJob extends Model {

  public static final DJobFinder find = new DJobFinder();

  @Id @Length(50)
  private final String id;

  @Version
  private long version;

  @NotNull @Length(50)
  private String owner;

  @NotNull
  private Instant whenExpire;

  public DJob(String id) {
    this.id = id;
    this.owner = "none";
    this.whenExpire = Instant.now().minusSeconds(60);
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public Instant getWhenExpire() {
    return whenExpire;
  }

  public void setWhenExpire(Instant whenExpire) {
    this.whenExpire = whenExpire;
  }
}
