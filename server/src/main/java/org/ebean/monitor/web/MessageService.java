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

  /**
   * Sentinel environment meaning "deliver on the app's next poll regardless of
   * which environment it reports". Used when a capture is requested without a
   * specific target environment. Apps never report this value themselves.
   */
  public static final String ANY_ENV = "*";

  private final ConcurrentMap<String, List<String>> responseMap = new ConcurrentHashMap<>();
  private final AtomicBoolean pendingResponse = new AtomicBoolean();

  public boolean pendingResponse() {
    return pendingResponse.get();
  }

  @Nullable
  public String responseBody(MetricRequest data) {
    // deliver both the env-specific bucket and the "any env" bucket for this app
    List<String> exact = responseMap.remove(key(data.appName(), data.environment()));
    List<String> any = responseMap.remove(key(data.appName(), ANY_ENV));
    if (responseMap.isEmpty()) {
      pendingResponse.set(false);
    }
    if (exact == null && any == null) {
      return null;
    }
    var sb = new StringBuilder(200);
    sb.append("v1");
    appendAll(sb, exact);
    appendAll(sb, any);
    return sb.toString();
  }

  private void appendAll(StringBuilder sb, @Nullable List<String> msgs) {
    if (msgs != null) {
      // the list has been removed from responseMap so this thread exclusively owns it
      for (String msg : msgs) {
        sb.append('|').append(msg);
      }
    }
  }

  public int pushMessage(String appName, String environment, String message) {
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
