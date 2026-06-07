package org.ebean.monitor.forward;

/**
 * One forward request handed to a {@link ForwardEngine}.
 *
 * @param kubectlRef         target reference, e.g. {@code svc/ebean-insight}
 * @param namespace          Kubernetes namespace
 * @param targetPort         remote container/service port
 * @param preferredLocalPort local port to bind; pinned by the supervisor for a
 *                           stable base URI. {@code 0} lets the engine pick.
 */
public record ForwardSpec(String kubectlRef, String namespace, int targetPort, int preferredLocalPort) {
}
