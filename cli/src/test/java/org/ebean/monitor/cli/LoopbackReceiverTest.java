package org.ebean.monitor.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackReceiverTest {

  @Test
  void capturesCodeAndState() throws Exception {
    try (var receiver = LoopbackReceiver.start(0)) {
      int port = receiver.port();

      CompletableFuture<LoopbackReceiver.CallbackResult> result =
          CompletableFuture.supplyAsync(() -> receiver.await(Duration.ofSeconds(5)));

      var http = HttpClient.newHttpClient();
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:" + port + "/callback?code=auth-code-1&state=state-1"))
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("Signed in");

      var cb = result.get();
      assertThat(cb).isNotNull();
      assertThat(cb.code()).isEqualTo("auth-code-1");
      assertThat(cb.state()).isEqualTo("state-1");
      assertThat(cb.error()).isNull();
    }
  }

  @Test
  void capturesError() throws Exception {
    try (var receiver = LoopbackReceiver.start(0)) {
      int port = receiver.port();

      CompletableFuture<LoopbackReceiver.CallbackResult> result =
          CompletableFuture.supplyAsync(() -> receiver.await(Duration.ofSeconds(5)));

      var http = HttpClient.newHttpClient();
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:" + port
              + "/callback?error=access_denied&error_description=User%20cancelled"))
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(400);

      var cb = result.get();
      assertThat(cb).isNotNull();
      assertThat(cb.error()).isEqualTo("access_denied");
      assertThat(cb.errorDescription()).isEqualTo("User cancelled");
      assertThat(cb.code()).isNull();
    }
  }
}
