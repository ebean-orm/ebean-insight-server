package org.ebean.monitor.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "gauge_m60")
public class DGaugeRollupM60 extends BaseGaugeRollup {

  public DGaugeRollupM60(Instant endTime, DGaugeRollup gauge) {
    super(endTime, gauge);
  }

}
