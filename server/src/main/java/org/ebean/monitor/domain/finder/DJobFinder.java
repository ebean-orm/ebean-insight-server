package org.ebean.monitor.domain.finder;

import io.ebean.Finder;
import org.ebean.monitor.domain.DJob;
import org.ebean.monitor.domain.query.QDJob;

public class DJobFinder extends Finder<String,DJob> {

  /**
   * Id for the rollup job.
   */
  public static final String ROLLUPM1 = "ROLLUPM1";

  /**
   * Construct using the default Database.
   */
  public DJobFinder() {
    super(DJob.class);
  }

  public void initRollup() {
    final int count = new QDJob().findCount();
    if (count == 0) {
      new DJob(ROLLUPM1).save();
    }
  }

  public DJob findRollup() {
    return new QDJob()
      .id.eq(ROLLUPM1)
      .findOne();
  }
}
