package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

@Json
public class PendingResponse {

  /**
   * The number of pending messages queued for the app+environment.
   */
  public int pending;
}
