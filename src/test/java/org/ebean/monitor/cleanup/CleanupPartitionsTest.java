package org.ebean.monitor.cleanup;

import org.junit.jupiter.api.Test;

public class CleanupPartitionsTest {

  @Test
  public void run() {
    new CleanupPartitions().run();
  }
}
