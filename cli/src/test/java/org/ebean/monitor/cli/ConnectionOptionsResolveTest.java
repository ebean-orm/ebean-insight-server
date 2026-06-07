package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionOptionsResolveTest {

  private static InsightConfig config(Path dir) {
    return new InsightConfig(dir.resolve("config.properties"));
  }

  @Test
  void noDefaults_andNothingConfigured_failsForwardTarget(@TempDir Path dir) {
    var conn = new ConnectionOptions();
    conn.resolve(config(dir));

    assertThat(conn.hasUrl()).isFalse();
    assertThatThrownBy(conn::requireForwardTarget)
        .isInstanceOf(CliException.class)
        .hasMessageContaining("No port-forward target configured");
  }

  @Test
  void configFile_suppliesNamespaceServiceAndPortDefault(@TempDir Path dir) {
    var cfg = config(dir);
    cfg.set("namespace", "dev-core");
    cfg.set("service", "central-insight");

    var conn = new ConnectionOptions();
    conn.resolve(cfg);
    conn.requireForwardTarget();

    assertThat(conn.namespace()).isEqualTo("dev-core");
    assertThat(conn.service()).isEqualTo("central-insight");
    assertThat(conn.targetPort()).isEqualTo(8091); // built-in fallback
    assertThat(conn.localPort()).isEqualTo(0);
    assertThat(conn.readySeconds()).isEqualTo(20L);
  }

  @Test
  void explicitFlag_overridesConfigFile(@TempDir Path dir) {
    var cfg = config(dir);
    cfg.set("namespace", "dev-core");
    cfg.set("service", "central-insight");
    cfg.set("target-port", "9000");

    var conn = new ConnectionOptions();
    conn.namespace = "prod-core";          // simulate --namespace prod-core
    conn.targetPort = 1234;                // simulate --target-port 1234
    conn.resolve(cfg);

    assertThat(conn.namespace()).isEqualTo("prod-core");   // flag wins
    assertThat(conn.service()).isEqualTo("central-insight"); // from config
    assertThat(conn.targetPort()).isEqualTo(1234);         // flag wins over config 9000
  }

  @Test
  void urlSatisfiesConnection_withoutForwardTarget(@TempDir Path dir) {
    var conn = new ConnectionOptions();
    conn.url = "http://localhost:8091";
    conn.resolve(config(dir));

    assertThat(conn.hasUrl()).isTrue();
    assertThat(conn.url()).isEqualTo("http://localhost:8091");
  }

  @Test
  void invalidNumericConfig_isReported(@TempDir Path dir) {
    var cfg = config(dir);
    cfg.set("target-port", "notnum");

    var conn = new ConnectionOptions();
    assertThatThrownBy(() -> conn.resolve(cfg))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("target-port is not a number");
  }
}
