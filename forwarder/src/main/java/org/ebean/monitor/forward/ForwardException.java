package org.ebean.monitor.forward;

/** Raised when a port-forward cannot be established or is lost. */
public class ForwardException extends RuntimeException {

  /** Classifies a failure so the supervisor can react appropriately. */
  public enum Kind {
    /** A generic, retryable failure. */
    GENERIC,
    /** The requested local port is already in use; the supervisor re-picks a port. */
    BIND_CONFLICT,
    /** No Ready pod is currently available for the target. */
    NO_POD,
    /** A non-retryable failure (auth/config); the supervisor aborts immediately. */
    FATAL
  }

  private final Kind kind;

  public ForwardException(String message) {
    this(Kind.GENERIC, message);
  }

  public ForwardException(String message, Throwable cause) {
    super(message, cause);
    this.kind = Kind.GENERIC;
  }

  public ForwardException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public ForwardException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
