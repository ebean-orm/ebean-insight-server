package org.ebean.monitor.ingest;

import io.ebean.DB;
import io.ebean.Database;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.rollup.Rollup;

import java.time.Instant;

import static org.ebean.monitor.ResourceHelp.metricRequest;

public class IngestMain {

  public static void main(String[] args) {

    System.out.println(System.currentTimeMillis());

    Database db = DB.getDefault();

    ProcessHeader header = new ProcessHeader();
    ProcessMetrics lookupMetrics = new ProcessMetrics();
    IngestMessage ingest = new IngestMessage(db, header, lookupMetrics);

    ingest.ingest(req("/example2/ats-1.json"));
    ingest.ingest(req("/example2/ats-1b.json"));
    ingest.ingest(req("/example2/ats-1c.json"));
    ingest.ingest(req("/example2/ats-1d.json"));

    ingest.ingest(req("/example2/mp-1.json"));
    ingest.ingest(req("/example2/mp-1b.json"));

    ingest.ingest(req("/example2/oa-1.json"));
    ingest.ingest(req("/example2/oa-1b.json"));
    ingest.ingest(req("/example2/oa-1c.json"));

    ingest.ingest(req("/example2/os-1.json"));

    ingest.ingest(req("/example2/sj-1.json"));
    ingest.ingest(req("/example2/sj-1b.json"));


    final Instant asOf = Instant.parse("2019-12-11T01:19:00.00Z");
    Rollup rollup = new Rollup(db, asOf);
    rollup.rollup();
  }

  private static MetricRequest req(String resourcePath) {
    final MetricRequest request = metricRequest(resourcePath);
    request.key = "testHash";
    return request;
  }
}
