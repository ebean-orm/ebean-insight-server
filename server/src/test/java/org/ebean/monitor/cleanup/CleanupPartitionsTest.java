package org.ebean.monitor.cleanup;

import io.avaje.inject.test.InjectTest;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class CleanupPartitionsTest {

  @Test
  void run() {
    new CleanupPartitions().run();
  }

  @Test
  void cleanupCaptureRequests_deletesOldKeepsRecent() {
    var app = new DApp("cleanup-capture-app");
    app.save();

    new DCaptureRequest(app, "cleanup-old-hash")
      .setRequestedAt(Instant.now().minus(Duration.ofDays(60)))
      .save();
    new DCaptureRequest(app, "cleanup-recent-hash")
      .setRequestedAt(Instant.now())
      .save();

    new CleanupPartitions().cleanupCaptureRequests();

    assertThat(new QDCaptureRequest().hash.eq("cleanup-old-hash").exists()).isFalse();
    assertThat(new QDCaptureRequest().hash.eq("cleanup-recent-hash").exists()).isTrue();
  }
}
