package org.ebean.monitor.forward;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Immutable status snapshot delivered to status listeners.
 *
 * @param state          current lifecycle state
 * @param baseUri        the stable local base URI once READY (else may be null)
 * @param reconnectCount consecutive reconnect attempts since the last READY
 * @param lastError      the most recent error, if any
 */
public record ForwardStatus(
    ForwardState state,
    @Nullable URI baseUri,
    int reconnectCount,
    @Nullable Throwable lastError) {
}
