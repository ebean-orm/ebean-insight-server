package org.ebean.monitor.v1.client;

import io.avaje.http.api.Client;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;

/**
 * Marker for avaje-http-client-generator to produce concrete HttpClient
 * implementations for the /v1 API tag interfaces.
 *
 * <p>Generated classes: {@code AppsApiHttpClient}, {@code EnvsApiHttpClient},
 * {@code MetricsApiHttpClient}, {@code PlansApiHttpClient}.
 */
@Client.Import({AppsApi.class, EnvsApi.class, MetricsApi.class, PlansApi.class})
public interface V1Clients {
}
