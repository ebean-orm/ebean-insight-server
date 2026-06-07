package org.ebean.monitor.cli;

/**
 * A user-facing error (bad usage or configuration). The CLI's execution-exception
 * handler prints {@link #getMessage()} without a stack trace and exits with code 2.
 */
final class CliException extends RuntimeException {

  CliException(String message) {
    super(message);
  }
}
