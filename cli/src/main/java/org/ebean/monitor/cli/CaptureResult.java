package org.ebean.monitor.cli;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * Result of a single plan-capture request, used for {@code insight capture}
 * JSON output when capturing one or more hashes.
 */
@Json
record CaptureResult(String hash, @Nullable Integer pending, @Nullable String error) {
}
