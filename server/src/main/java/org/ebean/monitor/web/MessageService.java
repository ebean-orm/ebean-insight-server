package org.ebean.monitor.web;

import jakarta.inject.Singleton;
import org.ebean.monitor.api.MetricRequest;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class MessageService {

  private final ConcurrentMap<String, List<String>> responseMap = new ConcurrentHashMap<>();
  private final AtomicBoolean pendingResponse = new AtomicBoolean();

  public boolean pendingResponse() {
    return pendingResponse.get();
  }

  @Nullable
  public String responseBody(MetricRequest data) {
    String key = key(data.appName, data.environment);
    List<String> msgs = responseMap.remove(key);
    if (msgs == null) {
      if (responseMap.isEmpty()) {
        pendingResponse.set(false);
      }
      return null;
    }
    var sb = new StringBuilder(200);
    sb.append("v1");
    for (String msg : msgs) {
      sb.append('|').append(msg);
    }
    return sb.toString();
  }

  public int pushMessage(String appName, String environment, String message) {
    String key = key(appName, environment);
    List<String> msgs = responseMap.computeIfAbsent(key, k -> Collections.synchronizedList(new LinkedList<>()));
    msgs.add(message);
    pendingResponse.set(true);
    return msgs.size();
  }

  /**
   * A read-only snapshot of a queued, not-yet-delivered message.
   */
  public record Pending(String app, String env, String message) {}

  /**
   * Return a read-only snapshot of all queued messages awaiting delivery.
   *
   * <p>This does not remove anything from the queue. The snapshot is inherently
   * racy: entries vanish as soon as the owning app polls.
   */
  public List<Pending> pendingSnapshot() {
    var result = new java.util.ArrayList<Pending>();
    responseMap.forEach((key, msgs) -> {
      int sep = key.lastIndexOf('|');
      String app = sep < 0 ? key : key.substring(0, sep);
      String env = sep < 0 ? "" : key.substring(sep + 1);
      synchronized (msgs) {
        for (String msg : msgs) {
          result.add(new Pending(app, env, msg));
        }
      }
    });
    return result;
  }

  private static String key(String appName, String environment) {
    var env = environment == null ? "no-environment" : environment;
    return appName + '|' + env;
  }
}
