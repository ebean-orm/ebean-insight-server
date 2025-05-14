package org.ebean.monitor.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "gauge_d1")
public class DGaugeRollupD1 extends BaseGaugeRollup {

  public DGaugeRollupD1(Instant endTime, DGaugeRollup gauge) {
    super(endTime, gauge);
  }

}
