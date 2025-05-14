package org.ebean.monitor.rollup;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.DJob;
import org.junit.jupiter.api.Test;

@InjectTest
public class RollupServiceTest {

  @Inject Database database;

  @Test
  public void run() {
    DJob.find.initRollup();
    RollupService rollupService = new RollupService(database);
    rollupService.run();
  }
}
