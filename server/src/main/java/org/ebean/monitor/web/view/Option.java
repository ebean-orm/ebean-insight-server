package org.ebean.monitor.web.view;

/**
 * A single dropdown option shared by the dashboard filter forms (app/env/range
 * selectors on both {@code query-total} and {@code metric-detail} pages).
 */
public record Option(String value, String label, boolean selected) {
}
