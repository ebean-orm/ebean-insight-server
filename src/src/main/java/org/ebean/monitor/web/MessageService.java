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
final class MessageService {

  private final ConcurrentMap<String, List<String>> responseMap = new ConcurrentHashMap<>();
  private final AtomicBoolean pendingResponse = new AtomicBoolean();

  boolean pendingResponse() {
    return pendingResponse.get();
  }

  @Nullable
  String responseBody(MetricRequest data) {
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

  int pushMessage(String appName, String environment, String message) {
    String key = key(appName, environment);
    List<String> msgs = responseMap.computeIfAbsent(key, k -> Collections.synchronizedList(new LinkedList<>()));
    msgs.add(message);
    pendingResponse.set(true);
    return msgs.size();
  }

  private static String key(String appName, String environment) {
    var env = environment == null ? "no-environment" : environment;
    return appName + '|' + env;
  }
}
