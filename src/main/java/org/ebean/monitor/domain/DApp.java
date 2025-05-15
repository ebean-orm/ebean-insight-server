package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import org.ebean.monitor.domain.finder.DAppFinder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The "Application" metrics relate to.
 */
@Cache(nearCache = true, naturalKey = {"org", "name"})
@Entity
@Table(name = "ebean_insight.app")
public class DApp extends BaseDomain {

  public static final DAppFinder find = new DAppFinder();

  @Column(nullable = false, length = 200)
  private String name;

  public DApp(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
