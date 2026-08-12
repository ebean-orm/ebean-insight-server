package org.ebean.monitor.ui;

import java.time.Instant;

record UiLoginTransaction(
  String state,
  String nonce,
  String codeVerifier,
  String returnPath,
  Instant expiresAt,
  String previousSessionId) {}
