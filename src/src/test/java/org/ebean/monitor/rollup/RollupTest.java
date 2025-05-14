package org.ebean.monitor.rollup;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@InjectTest
class RollupTest {

  @Inject
  Database database;

  @Test
  void mod10() {
    var rollup = new Rollup(database, Instant.parse("2020-10-01T00:15:30.00Z"));
    assertTrue(rollup.mod10(0));
    assertTrue(rollup.mod10(10));
    assertTrue(rollup.mod10(20));
  }

  @Test
  void mod10_false() {
    var rollup = new Rollup(database, Instant.parse("2020-10-01T00:15:30.00Z"));
    assertFalse(rollup.mod10(1));
    assertFalse(rollup.mod10(21));
    for (int i = 1; i < 10; i++) {
      assertFalse(rollup.mod10(i));
    }
  }
}
