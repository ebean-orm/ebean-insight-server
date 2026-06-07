package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForwardTargetTest {

  @Test
  void service_mapsToSvcRef() {
    var t = ForwardTarget.service("dev-core", "central-insight", 8091);
    assertThat(t.namespace()).isEqualTo("dev-core");
    assertThat(t.targetPort()).isEqualTo(8091);
    assertThat(t.kubectlRef()).isEqualTo("svc/central-insight");
  }

  @Test
  void deployment_mapsToDeployRef() {
    var t = ForwardTarget.deployment("dev-core", "central-insight", 8091);
    assertThat(t.kubectlRef()).isEqualTo("deploy/central-insight");
  }

  @Test
  void podSelector_kubectlRef_unsupported() {
    var t = new ForwardTarget.PodSelector("dev-core", Map.of("app", "central-insight"), 8091);
    assertThatThrownBy(t::kubectlRef).isInstanceOf(UnsupportedOperationException.class);
  }
}
