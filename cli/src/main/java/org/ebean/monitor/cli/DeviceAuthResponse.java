package org.ebean.monitor.cli;

import io.avaje.jsonb.Json;
import io.avaje.jsonb.Jsonb;
import org.jspecify.annotations.Nullable;

/**
 * Response from the OAuth 2.0 Device Authorization endpoint (RFC 8628).
 * Field names use {@code lower_underscore} to match the JSON wire format.
 */
@Json(naming = Json.Naming.LowerUnderscore)
record DeviceAuthResponse(
    String deviceCode,
    String userCode,
    String verificationUri,
    @Nullable String verificationUriComplete,
    int expiresIn,
    int interval) {

  /** Deserialise the raw JSON body from the device_authorization endpoint. */
  static DeviceAuthResponse parse(String json) {
    return Jsonb.instance().type(DeviceAuthResponse.class).fromJson(json);
  }
}
