package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricRequest;

import jakarta.inject.Singleton;
import org.ebean.monitor.api.QueryPlanRequest;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Singleton
public class IngestQueue {

  private final BlockingQueue<MetricRequest> queue = new LinkedBlockingQueue<>();
  private final BlockingQueue<QueryPlanRequest> queryPlanQueue = new LinkedBlockingQueue<>();

  public void put(MetricRequest request) {
    queue.add(request);
  }

  public MetricRequest poll() {
    return queue.poll();
  }

  public void putQueryPlans(QueryPlanRequest data) {
    queryPlanQueue.add(data);
  }

  public QueryPlanRequest pollQueryPlans() {
    return queryPlanQueue.poll();
  }

}
