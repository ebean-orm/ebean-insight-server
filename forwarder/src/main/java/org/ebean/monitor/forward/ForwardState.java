package org.ebean.monitor.forward;

/** Lifecycle state of a {@link SupervisedForwarder}. */
public enum ForwardState {

  /** Establishing (or re-establishing) the forward. */
  STARTING,

  /** The local port is bound and the target is reachable. */
  READY,

  /** The forward dropped and is being re-established. */
  RECONNECTING,

  /** Stopped via {@link SupervisedForwarder#close()}. */
  STOPPED,

  /** Permanently failed (gave up retrying). */
  FAILED
}
