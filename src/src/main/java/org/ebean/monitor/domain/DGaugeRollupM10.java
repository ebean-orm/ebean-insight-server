package org.ebean.monitor.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "gauge_m10")
public class DGaugeRollupM10 extends BaseGaugeRollup {

  public DGaugeRollupM10(Instant endTime, DGaugeRollup gauge) {
    super(endTime, gauge);
  }

}
