package org.ebean.monitor.ui;

final class UiTokenException extends RuntimeException {
  UiTokenException(String message) {
    super(message);
  }

  UiTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
