package org.ebean.monitor.forward;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * What to port-forward to — always a Service or Deployment (or a label selector),
 * never a pinned pod, so a fresh Ready pod is resolved on every (re)connect.
 */
public sealed interface ForwardTarget {

  /** The Kubernetes namespace. */
  String namespace();

  /** The container/service port to forward to. */
  int targetPort();

  /**
   * The {@code kubectl port-forward} target reference, for example
   * {@code svc/ebean-insight} or {@code deploy/ebean-insight}.
   *
   * @throws UnsupportedOperationException for a {@link PodSelector}, which must be
   *     resolved to a concrete pod before it can be used with the kubectl engine.
   */
  String kubectlRef();

  static ForwardTarget service(String namespace, String name, int targetPort) {
    return new Service(namespace, name, targetPort);
  }

  static ForwardTarget deployment(String namespace, String name, int targetPort) {
    return new Deployment(namespace, name, targetPort);
  }

  record Service(String namespace, String name, int targetPort) implements ForwardTarget {
    public Service {
      requireNonNull(namespace, "namespace");
      requireNonNull(name, "name");
    }

    @Override
    public String kubectlRef() {
      return "svc/" + name;
    }
  }

  record Deployment(String namespace, String name, int targetPort) implements ForwardTarget {
    public Deployment {
      requireNonNull(namespace, "namespace");
      requireNonNull(name, "name");
    }

    @Override
    public String kubectlRef() {
      return "deploy/" + name;
    }
  }

  record PodSelector(String namespace, Map<String, String> labels, int targetPort) implements ForwardTarget {
    public PodSelector {
      requireNonNull(namespace, "namespace");
      labels = Map.copyOf(labels);
    }

    @Override
    public String kubectlRef() {
      throw new UnsupportedOperationException(
          "PodSelector requires pod resolution; use a Service or Deployment target with the kubectl engine");
    }
  }
}
