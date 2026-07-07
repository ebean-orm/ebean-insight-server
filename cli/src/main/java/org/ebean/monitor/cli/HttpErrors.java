package org.ebean.monitor.cli;

import io.avaje.http.client.HttpException;

/**
 * Formats {@link HttpException} status codes for CLI output.
 * <p>
 * avaje-http-client synthesizes status {@code 499} whenever the underlying Java
 * {@code HttpClient} throws before any real HTTP response is received — DNS
 * resolution failure, connection refused, connect/TLS timeout, etc. Printing
 * that as a bare {@code "HTTP 499"} looks like a genuine (and misleading) server
 * response, so for that case this surfaces the real cause instead.
 */
final class HttpErrors {

  private HttpErrors() {
  }

  /**
   * Returns a short description of the failure, e.g. {@code "HTTP 404"} for a
   * real HTTP response, or the underlying exception type/message (e.g.
   * {@code "UnresolvedAddressException"}) when the status is the synthetic 499.
   */
  static String describe(HttpException e) {
    Throwable cause = e.getCause();
    if (e.statusCode() == 499 && cause != null) {
      return cause.getMessage() != null
          ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
          : cause.getClass().getSimpleName();
    }
    return "HTTP " + e.statusCode();
  }
}
