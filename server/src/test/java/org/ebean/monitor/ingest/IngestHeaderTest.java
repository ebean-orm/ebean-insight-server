package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class IngestHeaderTest {

  @Test
  public void truncate() {

    final Instant now = Instant.parse("2019-11-19T10:40:34.006461Z");

    final Instant truncated = IngestHeader.truncate(now);
    assertThat(truncated.toString()).isEqualTo("2019-11-19T10:40:00Z");
  }
}
