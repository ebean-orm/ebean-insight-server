package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DeriveGroupTest {

  @Test
  public void of_expect_null() {
    assertNull(DeriveGroup.of("l2n"));
    assertNull(DeriveGroup.of("other"));
    assertNull(DeriveGroup.of("other.hit"));
  }

  @Test
  public void of_l2NearCache() {
    assertEquals("l2n.hit", DeriveGroup.of("l2n.DOrg_N.hit"));
    assertEquals("l2n.miss", DeriveGroup.of("l2n.DOrg_N.miss"));
    assertEquals("l2n.put", DeriveGroup.of("l2n.DOrg_N.put"));
    assertEquals("l2n.evict", DeriveGroup.of("l2n.DOrg_N.evict"));
  }

  @Test
  public void of_l2RemoteCache() {
    assertEquals("l2r.hit", DeriveGroup.of("l2r.DOrg_N.hit"));
    assertEquals("l2r.miss", DeriveGroup.of("l2r.DOrg_N.miss"));
    assertEquals("l2r.put", DeriveGroup.of("l2r.DOrg_N.put"));
    assertEquals("l2r.evict", DeriveGroup.of("l2r.DOrg_N.evict"));
  }

  @Test
  public void of_iud() {
    assertEquals("iud.insert", DeriveGroup.of("iud.DOrg.insert"));
    assertEquals("iud.insert", DeriveGroup.of("iud.DOrg.insertBatch"));
    assertEquals("iud.update", DeriveGroup.of("iud.DOrg.update"));
    assertEquals("iud.update", DeriveGroup.of("iud.DOrg.updateBatch"));
    assertEquals("iud.delete", DeriveGroup.of("iud.DOrg.delete"));
    assertEquals("iud.delete", DeriveGroup.of("iud.DOrg.deleteBatch"));

    assertEquals("iud.junk", DeriveGroup.of("iud.DOrg.junk"));
    assertEquals("iud.insert", DeriveGroup.of("iud.DOrg.insertJunk"));
  }
}
