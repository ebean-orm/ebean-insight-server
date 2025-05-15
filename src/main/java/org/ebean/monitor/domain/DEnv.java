package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import org.ebean.monitor.domain.finder.DEnvFinder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The "Environment" the metrics relate to.
 * <p>
 * For example "prod", "dev", "sand-pit".
 * </p>
 */
@Cache(nearCache = true, naturalKey = {"name"})
@Entity
@Table(name = "ebean_insight.env")
public class DEnv extends BaseDomain {

  public static final DEnvFinder find = new DEnvFinder();

  /**
   * The environment name.
   */
  @Column(nullable = false, length = 50)
  private final String name;

  public DEnv(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
