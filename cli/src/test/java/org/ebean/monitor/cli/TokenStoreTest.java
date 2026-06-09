package org.ebean.monitor.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TokenStoreTest {

  private static TokenData sample() {
    return new TokenData("access-123", "refresh-456", "id-789", "Bearer", 2_000L, 1_000L);
  }

  @Test
  void saveLoad_roundTrips(@TempDir Path dir) {
    var store = new TokenStore(dir.resolve("token.json"));
    assertThat(store.load()).isEmpty();

    store.save(sample());

    var loaded = store.load().orElseThrow();
    assertThat(loaded.accessToken()).isEqualTo("access-123");
    assertThat(loaded.refreshToken()).isEqualTo("refresh-456");
    assertThat(loaded.idToken()).isEqualTo("id-789");
    assertThat(loaded.tokenType()).isEqualTo("Bearer");
    assertThat(loaded.expiresAt()).isEqualTo(2_000L);
    assertThat(loaded.obtainedAt()).isEqualTo(1_000L);
  }

  @Test
  void clear_removesFile(@TempDir Path dir) {
    var store = new TokenStore(dir.resolve("token.json"));
    store.save(sample());
    assertThat(store.clear()).isTrue();
    assertThat(store.load()).isEmpty();
    assertThat(store.clear()).isFalse();
  }

  @Test
  void isExpired_appliesSkew() {
    var token = new TokenData("a", null, null, null, 1_000L, 0L);
    assertThat(token.isExpired(900)).isFalse();
    assertThat(token.isExpired(971)).isTrue();   // within 30s skew of 1000
    assertThat(token.isExpired(1_000)).isTrue();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void save_restrictsPermissionsToOwner(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("token.json");
    var store = new TokenStore(file);
    store.save(sample());

    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
    assertThat(perms).containsExactlyInAnyOrder(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  }
}
