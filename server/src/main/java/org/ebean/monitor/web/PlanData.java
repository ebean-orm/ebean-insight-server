package org.ebean.monitor.web;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * JSON payload embedded on the {@code query-plan} page and handed to the
 * PEV2 visualizer (running in an isolated iframe, see
 * {@code static/pev2-frame.html}) via {@code postMessage} once it signals
 * ready - avoids embedding large/arbitrary captured plan text directly into
 * an HTML attribute or JS string literal.
 */
@Json
record PlanData(@Nullable String plan, @Nullable String sql) {
}
