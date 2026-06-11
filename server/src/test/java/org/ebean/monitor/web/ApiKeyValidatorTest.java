package org.ebean.monitor.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyValidatorTest {

  @Test
  void disabled_whenNoKeys_returnsNull() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of());
    assertThat(validator.enabled()).isFalse();
    assertThat(validator.principalFor(null)).isNull();
    assertThat(validator.principalFor("whatever")).isNull();
  }

  @Test
  void enabled_withMatchingKey_returnsPrincipal() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of("secret-1"));
    assertThat(validator.enabled()).isTrue();
    assertThat(validator.principalFor("secret-1")).isEqualTo(ApiKeyValidator.PRINCIPAL);
  }

  @Test
  void enabled_withMissingKey_returnsNull() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of("secret-1"));
    assertThat(validator.principalFor(null)).isNull();
  }

  @Test
  void enabled_withEmptyKey_returnsNull() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of("secret-1"));
    assertThat(validator.principalFor("")).isNull();
  }

  @Test
  void enabled_withWrongKey_returnsNull() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of("secret-1"));
    assertThat(validator.principalFor("nope")).isNull();
  }

  @Test
  void multipleKeys_eitherMatches_forRotation() {
    ApiKeyValidator validator = new ApiKeyValidator(List.of("old-key", "new-key"));
    assertThat(validator.principalFor("old-key")).isEqualTo(ApiKeyValidator.PRINCIPAL);
    assertThat(validator.principalFor("new-key")).isEqualTo(ApiKeyValidator.PRINCIPAL);
    assertThat(validator.principalFor("other")).isNull();
  }
}
