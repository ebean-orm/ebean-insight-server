package org.ebean.monitor.rollup;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.DJob;
import org.junit.jupiter.api.Test;

import java.util.List;

@InjectTest
public class RollupServiceTest {

  @Inject Database database;

  @Test
  public void run() {
    DJob.find.initRollup();
    var noopTrigger = new RollupPlanTrigger(_ -> List.of(), _ -> List.of(), _ -> List.of(), (_, _, _) -> {}, System::currentTimeMillis);
    RollupService rollupService = new RollupService(database, noopTrigger);
    rollupService.run();
  }
}
