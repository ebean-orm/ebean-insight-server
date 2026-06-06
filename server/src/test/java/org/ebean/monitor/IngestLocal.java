package org.ebean.monitor;

import io.avaje.http.client.HttpClient;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ebean.monitor.ResourceHelp.read;

public class IngestLocal {

  static HttpClient httpClient = HttpClient.builder()
    .baseUrl("http://localhost:8091")
    .build();

  public static void main(String[] args) {
    ingest(read("/examples/oas-0.json"));
  }

  private static void ingest(String bodyA) {
    HttpResponse<String> hres = httpClient.request()
      .path("api/ingest/metrics")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "Fsd45SDfd7")
      .body(bodyA)
      .POST()
      .asString();

    String body = hres.body();
    System.out.println("body: " + body);
    assertThat(hres.statusCode()).isEqualTo(204);
  }
}
