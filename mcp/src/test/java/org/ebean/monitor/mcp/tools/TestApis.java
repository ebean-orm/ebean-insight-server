package org.ebean.monitor.mcp.tools;

import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test doubles for the {@code /v1} API interfaces, implemented as dynamic
 * proxies so they remain valid as the generated API interfaces gain methods
 * (the OpenAPI contract evolves independently). Each proxy:
 * <ul>
 *   <li>records {@code method name -> last call arguments} (see {@link #args}),</li>
 *   <li>returns canned data for the handful of methods the tests assert on,</li>
 *   <li>returns a safe default otherwise — an empty {@code List} for list
 *       returns, else {@code null}.</li>
 * </ul>
 */
public final class TestApis {

  /** Records {@code method name -> last call arguments}. */
  public final Map<String, Object[]> calls = new HashMap<>();

  /** Canned return values keyed by method name. */
  private final Map<String, Object> canned = new HashMap<>();

  public final AppsApi apps;
  public final EnvsApi envs;
  public final MetricsApi metrics;
  public final PlansApi plans;

  public TestApis() {
    canned.put("listApps", List.of(new App(1L, "central-access"), new App(2L, "central-notifications")));
    canned.put("listEnvs", List.of(new Env("test"), new Env("dev")));
    canned.put("listPlans", List.of(sampleSummary(15L)));
    canned.put("getPlan", samplePlan(15L));
    canned.put("requestPlanCapture", new PendingResponse(1, "central-access", "test", "orm.X.find"));
    canned.put("getPlanChange", samplePlanChangeDetail(7L));
    canned.put("getMetricTimeseries",
        new MetricTimeseries("central-access", "hash1", "orm.X.find", 60L, 5L, List.of()));

    apps = proxy(AppsApi.class, new Recorder(calls, canned));
    envs = proxy(EnvsApi.class, new Recorder(calls, canned));
    metrics = proxy(MetricsApi.class, new Recorder(calls, canned));
    plans = proxy(PlansApi.class, new Recorder(calls, canned));
  }

  public Object[] args(String method) {
    return calls.get(method);
  }

  public boolean called(String method) {
    return calls.containsKey(method);
  }

  static QueryPlan samplePlan(long id) {
    return new QueryPlan(id, "hash" + id, "ebean.query", "orm.X.find", java.util.Map.of(), 1L, "test",
        100L, 1L, 100L, Instant.parse("2026-06-01T00:00:00Z"),
        "select 1", "[]", "Seq Scan", "shape", "h", 1);
  }

  static QueryPlanSummary sampleSummary(long id) {
    return new QueryPlanSummary(id, 1L, "test", "hash" + id, "ebean.query", "orm.X.find", java.util.Map.of(),
        100L, 1L, Instant.parse("2026-06-01T00:00:00Z"), "shape", false);
  }

  static PlanChangeDetail samplePlanChangeDetail(long id) {
    PlanChange change = new PlanChange(id, "central-access", "test", "hash1", "orm.X.find",
        "ebean.query", java.util.Map.of(),
        "CHANGED", 5L, 8L, "aaaaaaaa", "bbbbbbbb", 1, 100L, 200L,
        Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:01:00Z"));
    return new PlanChangeDetail(change, samplePlan(5L), samplePlan(8L));
  }

  /**
   * A {@link PlansApi} whose every {@code /v1} method throws — simulates the
   * upstream insight server being unreachable. Used to verify error handling.
   */
  public static PlansApi throwingPlans() {
    return proxy(PlansApi.class, (proxy, method, args) -> {
      Object handled = objectMethod(proxy, method, args);
      if (handled != NOT_OBJECT_METHOD) {
        return handled;
      }
      throw new RuntimeException("boom");
    });
  }

  private static <T> T proxy(Class<T> api, InvocationHandler handler) {
    return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler));
  }

  /** Sentinel distinguishing "this was an Object method" from a null return. */
  private static final Object NOT_OBJECT_METHOD = new Object();

  /** Handle equals/hashCode/toString on a proxy; return sentinel otherwise. */
  private static Object objectMethod(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "toString" -> "TestApiProxy(" + method.getDeclaringClass().getSimpleName() + ")";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> NOT_OBJECT_METHOD;
    };
  }

  /** Records the call and returns canned data or a type-appropriate default. */
  private record Recorder(Map<String, Object[]> calls, Map<String, Object> canned)
      implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      Object handled = objectMethod(proxy, method, args);
      if (handled != NOT_OBJECT_METHOD) {
        return handled;
      }
      calls.put(method.getName(), args == null ? new Object[0] : args);
      if (canned.containsKey(method.getName())) {
        return canned.get(method.getName());
      }
      return List.class.isAssignableFrom(method.getReturnType()) ? List.of() : null;
    }
  }
}
