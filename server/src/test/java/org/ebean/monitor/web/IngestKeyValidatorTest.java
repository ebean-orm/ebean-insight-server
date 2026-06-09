package org.ebean.monitor.web;

import io.avaje.jex.http.HttpResponseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class IngestKeyValidatorTest {

  @Test
  void disabled_whenNoKeys_acceptsAnything() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of());
    assertThat(validator.enabled()).isFalse();
    assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("whatever")).doesNotThrowAnyException();
  }

  @Test
  void enabled_withMatchingKey_passes() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of("secret-1"));
    assertThat(validator.enabled()).isTrue();
    assertThatCode(() -> validator.validate("secret-1")).doesNotThrowAnyException();
  }

  @Test
  void enabled_withMissingKey_throws401() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of("secret-1"));
    assertThatThrownBy(() -> validator.validate(null))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void enabled_withEmptyKey_throws401() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of("secret-1"));
    assertThatThrownBy(() -> validator.validate(""))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void enabled_withWrongKey_throws401() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of("secret-1"));
    assertThatThrownBy(() -> validator.validate("nope"))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void multipleKeys_eitherValidates_forRotation() {
    IngestKeyValidator validator = new IngestKeyValidator(List.of("old-key", "new-key"));
    assertThatCode(() -> validator.validate("old-key")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("new-key")).doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate("other"))
      .isInstanceOf(HttpResponseException.class);
  }
}
