package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import io.ebean.annotation.NotNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The "Database" of the Application the metrics relate to.
 * <p>
 * Most Applications will have 1 database (eg. "db")
 */
@Cache(nearCache = true, naturalKey = {"app", "name"})
@Entity
@Table(name = "ebean_insight.app_db")
public class DAppDatabase extends BaseDomain {

  /**
   * The Application this database belongs to.
   */
  @ManyToOne
  @NotNull
  private final DApp app;

  @Column(nullable = false, length = 200)
  private final String name;

  public DAppDatabase(DApp app, String shortName) {
    this.name = shortName;
    this.app = app;
  }

  public DApp getApp() {
    return app;
  }

  public String getName() {
    return name;
  }
}
