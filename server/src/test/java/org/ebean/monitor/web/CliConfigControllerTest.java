package org.ebean.monitor.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class CliConfigControllerTest {

  @Inject
  HttpClient httpClient;

  @Test
  void cliConfig_returnsJson() {
    HttpResponse<String> res = httpClient.request()
        .path("api/cli-config")
        .GET().asString();

    assertThat(res.statusCode()).isEqualTo(200);
    // endpoint is reachable and returns valid JSON object
    assertThat(res.body()).startsWith("{");
  }
}
