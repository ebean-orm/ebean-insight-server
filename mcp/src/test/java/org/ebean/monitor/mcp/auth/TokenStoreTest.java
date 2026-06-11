package org.ebean.monitor.mcp.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenStoreTest {

  @Test
  void disabled_whenNull() {
    TokenStore store = new TokenStore(null);
    assertThat(store.enabled()).isFalse();
    assertThat(store.principalFor("anything")).isNull();
  }

  @Test
  void disabled_whenBlank() {
    assertThat(new TokenStore("   ").enabled()).isFalse();
  }

  @Test
  void singleNamedToken() {
    TokenStore store = new TokenStore("claude:abc123");
    assertThat(store.enabled()).isTrue();
    assertThat(store.principalFor("abc123")).isEqualTo("claude");
    assertThat(store.principalFor("nope")).isNull();
  }

  @Test
  void multipleTokens_forRotation() {
    TokenStore store = new TokenStore("old:secret-1, new:secret-2");
    assertThat(store.principalFor("secret-1")).isEqualTo("old");
    assertThat(store.principalFor("secret-2")).isEqualTo("new");
    assertThat(store.principalFor("secret-3")).isNull();
  }

  @Test
  void trimsWhitespaceAroundNameValueAndEntries() {
    TokenStore store = new TokenStore("  cli : tok-1 ");
    assertThat(store.principalFor("tok-1")).isEqualTo("cli");
  }

  @Test
  void entryWithoutColon_labelledUnnamed() {
    TokenStore store = new TokenStore("rawsecret");
    assertThat(store.principalFor("rawsecret")).isEqualTo("unnamed");
  }

  @Test
  void blankEntriesIgnored() {
    TokenStore store = new TokenStore("a:1,,  ,b:2");
    assertThat(store.principalFor("1")).isEqualTo("a");
    assertThat(store.principalFor("2")).isEqualTo("b");
  }

  @Test
  void emptyValueIgnored() {
    TokenStore store = new TokenStore("name:");
    assertThat(store.enabled()).isFalse();
  }

  @Test
  void nullOrEmptyPresentedToken_returnsNull() {
    TokenStore store = new TokenStore("claude:abc123");
    assertThat(store.principalFor(null)).isNull();
    assertThat(store.principalFor("")).isNull();
  }
}
