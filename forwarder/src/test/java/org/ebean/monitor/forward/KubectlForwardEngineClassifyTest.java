package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KubectlForwardEngineClassifyTest {

  @Test
  void parseForwarding_extractsHostAndPort() {
    var addr = KubectlForwardEngine.parseForwarding("Forwarding from 127.0.0.1:34567 -> 8091");
    assertThat(addr).isNotNull();
    assertThat(addr.getHostString()).isEqualTo("127.0.0.1");
    assertThat(addr.getPort()).isEqualTo(34567);
  }

  @Test
  void parseForwarding_ignoresUnrelatedLine() {
    assertThat(KubectlForwardEngine.parseForwarding("Handling connection for 34567")).isNull();
  }

  @Test
  void bindConflict_detected() {
    assertThat(KubectlForwardEngine.isBindConflict(
        "error: unable to listen on any of the requested ports: [{34567 8091}]")).isTrue();
    assertThat(KubectlForwardEngine.isBindConflict("Forwarding from 127.0.0.1:34567 -> 8091")).isFalse();
  }

  @Test
  void dropMarkers_detected() {
    assertThat(KubectlForwardEngine.isDropMarker("E0607 lost connection to pod")).isTrue();
    assertThat(KubectlForwardEngine.isDropMarker("an error occurred forwarding 34567 -> 8091")).isTrue();
    assertThat(KubectlForwardEngine.isDropMarker("error upgrading connection: ...")).isTrue();
    assertThat(KubectlForwardEngine.isDropMarker("Handling connection for 34567")).isFalse();
  }

  @Test
  void fatalMarkers_detected() {
    assertThat(KubectlForwardEngine.isFatal(
        "aws: [ERROR]: Token has expired and refresh failed")).isTrue();
    assertThat(KubectlForwardEngine.isFatal(
        "error: getting credentials: exec: executable aws failed with exit code 255")).isTrue();
    assertThat(KubectlForwardEngine.isFatal("error: You must be logged in to the server (Unauthorized)")).isTrue();
    assertThat(KubectlForwardEngine.isFatal("error: context \"nope\" does not exist")).isTrue();
  }

  @Test
  void fatalMarkers_ignoreTransientAndNormalLines() {
    // network reachability is intentionally retryable, not fatal
    assertThat(KubectlForwardEngine.isFatal(
        "Unable to connect to the server: dial tcp: i/o timeout")).isFalse();
    assertThat(KubectlForwardEngine.isFatal("E0607 lost connection to pod")).isFalse();
    assertThat(KubectlForwardEngine.isFatal("Forwarding from 127.0.0.1:34567 -> 8091")).isFalse();
  }
}
