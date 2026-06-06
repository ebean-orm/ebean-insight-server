package org.ebean.monitor.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Header;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.http.api.Produces;
import io.avaje.jex.http.Context;
import io.avaje.json.JsonException;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.ingest.IngestQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
@Path("/api/ingest")
final class IngestController {

  private static final Logger log = LoggerFactory.getLogger(IngestController.class);

  private final MessageService messageService;
  private final IngestQueue queue;
  private final JsonType<MetricRequest> jsonMetricRequest;
  private final JsonType<QueryPlanRequest> jsonQueryPlans;

  IngestController(MessageService messageService, IngestQueue queue, Jsonb jsonb) {
    this.messageService = messageService;
    this.queue = queue;
    this.jsonMetricRequest = jsonb.type(MetricRequest.class);
    this.jsonQueryPlans = jsonb.type(QueryPlanRequest.class);
  }

  @Get
  @Produces("text/plain")
  String ack() {
    return "ok";
  }

  /**
   * Ingest the metrics.
   */
  @Post("/metrics")
  void ingest(@Header("Insight-key") String key, @Header("Content-Encoding") String encoding, Context context) {
    try {
      String content;
      if ("gzip".equals(encoding)) {
        content = GzipUtil.decompress(context.bodyAsBytes());
      } else {
        content = context.body();
      }
      MetricRequest data = jsonMetricRequest.fromJson(content);

      if (key != null) {
        data.key = key;
      }
      // put it on the queue and ingest into DB in the background
      queue.put(data);

      if (messageService.pendingResponse()) {
        final String responseBody = messageService.responseBody(data);
        if (responseBody != null) {
          context.status(200);
          context.contentType("text/plain");
          context.write(responseBody);
        }
      }

    } catch (Exception e) {
      log.error("Failed to ingest metrics", e);
    }
  }

  @Post("/plans")
  void ingestQueryPlans(@Header("Content-Encoding") String encoding, Context context) {
    try {
      String content;
      if ("gzip".equals(encoding)) {
        content = GzipUtil.decompress(context.bodyAsBytes());
      } else {
        content = context.body();
      }
      try {
        queue.putQueryPlans(jsonQueryPlans.fromJson(content));
      } catch (JsonException e) {
        log.error("Failed to parse queryPlan payload {}", content, e);
      }
    } catch (Exception e) {
      log.error("Failed to ingest query plans", e);
    }
  }
}
