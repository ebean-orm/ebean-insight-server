package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import io.ebean.annotation.NotNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Identifies an instance of the application (eg. K8 pod).
 */
@Cache(nearCache = true, naturalKey = {"app", "name"})
@Entity
@Table(name = "app_pod")
public class DAppPod extends BaseDomain {

  /**
   * The Application this pod belongs to.
   */
  @ManyToOne
  @NotNull
  private final DApp app;

  @Column(nullable = false, length = 200)
  private final String name;

  public DAppPod(DApp app, String name) {
    this.name = name;
    this.app = app;
  }

  public DApp getApp() {
    return app;
  }

  public String getName() {
    return name;
  }
}
