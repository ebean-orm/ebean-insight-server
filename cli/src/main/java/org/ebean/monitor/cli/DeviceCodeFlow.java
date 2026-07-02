package org.ebean.monitor.cli;

import io.avaje.json.mapper.JsonExtract;
import io.avaje.json.mapper.JsonMapper;
import io.avaje.oauth2.core.data.JsonDataMapper;
import io.avaje.oauth2.core.data.OidcTokens;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628) for Cognito.
 *
 * <p>Flow:
 * <ol>
 *   <li>POST to {@code {domain}/oauth2/device_authorization} → get {@code user_code} +
 *       {@code verification_uri}.</li>
 *   <li>Print the code and URL for the user to complete in a browser.</li>
 *   <li>Poll {@code {domain}/oauth2/token} until approved, expired, or denied.</li>
 * </ol>
 *
 * <p>No local HTTP server or redirect URI needed — works behind SSH tunnels and
 * in headless environments.
 */
final class DeviceCodeFlow {

  private final String domain;
  private final String clientId;
  private final String scope;
  private final @Nullable String profile;
  private final HttpClient http;
  private final JsonMapper jsonMapper;
  private final JsonDataMapper oidcMapper;

  DeviceCodeFlow(AuthConfig auth, @Nullable String profile) {
    this.domain = auth.domain();
    this.clientId = auth.clientId();
    this.scope = auth.scope();
    this.profile = profile;
    this.http = HttpClient.newHttpClient();
    this.jsonMapper = JsonMapper.builder().build();
    this.oidcMapper = JsonDataMapper.builder().jsonMapper(jsonMapper).build();
  }

  int login(long timeoutSeconds) {
    DeviceAuthResponse deviceAuth = startDeviceAuth();
    printInstructions(deviceAuth);
    OidcTokens tokens = poll(deviceAuth, timeoutSeconds);
    return new LoginHelper(profile).saveTokens(tokens);
  }

  private void printInstructions(DeviceAuthResponse auth) {
    System.out.println();
    if (auth.verificationUriComplete() != null) {
      System.out.println("Open this URL to sign in:");
      System.out.println("  " + auth.verificationUriComplete());
      System.out.println();
      System.out.println("Or open " + auth.verificationUri() + " and enter: " + auth.userCode());
    } else {
      System.out.println("Open this URL in your browser:");
      System.out.println("  " + auth.verificationUri());
      System.out.println();
      System.out.println("Enter this code: " + auth.userCode());
    }
    System.out.println();
  }

  private DeviceAuthResponse startDeviceAuth() {
    String body = "client_id=" + encode(clientId) + "&scope=" + encode(scope);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(domain + "/oauth2/device_authorization"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new CliException("Device authorization request failed (HTTP " + response.statusCode()
            + "): " + response.body());
      }
      return DeviceAuthResponse.parse(response.body());
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("Device authorization request failed: " + e.getMessage());
    }
  }

  private OidcTokens poll(DeviceAuthResponse deviceAuth, long timeoutSeconds) {
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    int intervalMs = deviceAuth.interval() * 1000;

    String body = "grant_type=" + encode("urn:ietf:params:oauth2:grant-type:device_code")
        + "&device_code=" + encode(deviceAuth.deviceCode())
        + "&client_id=" + encode(clientId);

    while (System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CliException("Device login interrupted.");
      }

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(domain + "/oauth2/token"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      try {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return oidcMapper.readOidcTokens(response.body());
        }
        JsonExtract err = JsonExtract.of(jsonMapper.fromJsonObject(response.body()));
        switch (err.extract("error", "unknown")) {
          case "authorization_pending" -> { /* keep polling */ }
          case "slow_down" -> intervalMs += 5000;
          case "expired_token" -> throw new CliException(
              "Device login timed out — the code has expired. Please run `insight login --device` again.");
          case "access_denied" -> throw new CliException("Device login denied by the user.");
          default -> {
            String msg = err.extract("error", "unknown");
            String desc = err.extract("error_description", "");
            throw new CliException("Device login failed: " + msg + (desc.isEmpty() ? "" : " — " + desc));
          }
        }
      } catch (IOException | InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CliException("Device login polling failed: " + e.getMessage());
      }
    }
    throw new CliException("Timed out waiting for device login to complete.");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
