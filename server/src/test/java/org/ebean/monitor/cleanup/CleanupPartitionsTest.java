package org.ebean.monitor.cleanup;

import io.avaje.inject.test.InjectTest;
import org.junit.jupiter.api.Test;

@InjectTest
class CleanupPartitionsTest {

  @Test
  void run() {
    new CleanupPartitions().run();
  }
}
