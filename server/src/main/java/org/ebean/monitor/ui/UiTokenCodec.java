package org.ebean.monitor.ui;

import io.avaje.config.Config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class UiTokenCodec {
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  private UiTokenCodec(byte[] keyBytes) {
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  static UiTokenCodec load() {
    String configured = Config.getNullable("insight.ui.auth.token-encryption-key");
    if (configured == null || configured.isBlank()) {
      throw new UiTokenException("UI OAuth token encryption key is not configured");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(configured.trim());
    } catch (IllegalArgumentException e) {
      throw new UiTokenException("UI OAuth token encryption key is not valid base64", e);
    }
    if (key.length != KEY_BYTES) {
      throw new UiTokenException("UI OAuth token encryption key must decode to 32 bytes");
    }
    return new UiTokenCodec(key);
  }

  String encrypt(String value) {
    if (value == null) return null;
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new UiTokenException("Unable to encrypt UI OAuth data", e);
    }
  }

  String decrypt(String value) {
    if (value == null) return null;
    try {
      byte[] combined = Base64.getDecoder().decode(value);
      if (combined.length <= NONCE_BYTES) throw new UiTokenException("Invalid encrypted UI OAuth data");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key,
        new GCMParameterSpec(TAG_BITS, combined, 0, NONCE_BYTES));
      return new String(cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES),
        StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new UiTokenException("Unable to decrypt UI OAuth data", e);
    }
  }

  static String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new UiTokenException("Unable to hash UI OAuth identifier", e);
    }
  }
}
