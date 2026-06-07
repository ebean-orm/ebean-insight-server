package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class KubectlForwardEngineExitTest {

  @Test
  void open_whenKubectlExitsWithError_surfacesStderr(@TempDir Path dir) throws IOException {
    Path fake = dir.resolve("fake-kubectl.sh");
    Files.writeString(fake, """
        #!/bin/sh
        echo "aws: [ERROR]: Token has expired and refresh failed" 1>&2
        echo "error: getting credentials: exec: executable aws failed with exit code 255" 1>&2
        exit 255
        """);
    Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));

    var engine = new KubectlForwardEngine(fake.toString(), null, Duration.ofSeconds(5));
    var spec = new ForwardSpec("svc/central-insight", "dev-core", 8091, 12345);

    assertThatThrownBy(() -> engine.open(spec))
        .isInstanceOfSatisfying(ForwardException.class,
            fe -> assertThat(fe.kind()).isEqualTo(ForwardException.Kind.FATAL))
        .hasMessageContaining("kubectl exited (code 255)")
        .hasMessageContaining("Token has expired");
  }
}
