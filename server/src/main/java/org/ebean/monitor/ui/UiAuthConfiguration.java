package org.ebean.monitor.ui;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.spi.JexPlugin;
import io.avaje.jsonb.Jsonb;
import io.ebean.Database;

@Factory
final class UiAuthConfiguration {

  @Bean
  UiAuthSettings uiAuthSettings() {
    return UiAuthSettings.load();
  }

  @Bean
  UiOidcClient uiOidcClient(UiAuthSettings settings) {
    return UiOidcClient.create(settings);
  }

  @Bean
  UiTokenVerifier uiTokenVerifier(UiAuthSettings settings) {
    return UiTokenVerifier.create(settings);
  }

  @Bean
  UiSessionStore uiSessionStore(UiAuthSettings settings, Database database) {
    if (settings.persistentStore()) {
      return new DatabaseUiSessionStore(database, UiTokenCodec.load());
    }
    return new InMemoryUiSessionStore();
  }

  @Bean
  UiLoginTransactionStore uiLoginTransactionStore(
    UiAuthSettings settings, Database database) {
    if (settings.persistentStore()) {
      return new DatabaseUiLoginTransactionStore(database, UiTokenCodec.load());
    }
    return new InMemoryUiLoginTransactionStore();
  }

  @Bean
  UiAuthService uiAuthService(
    UiAuthSettings settings,
    UiOidcClient oidc,
    UiTokenVerifier tokenVerifier,
    UiSessionStore sessions,
    UiLoginTransactionStore transactions,
    Jsonb jsonb) {
    return new UiAuthService(settings, oidc, tokenVerifier, sessions, transactions, jsonb);
  }

  @Bean
  JexPlugin uiAuthFilterPlugin(UiAuthSettings settings, UiAuthService service) {
    return jex -> {
      if (settings.enabled()) {
        jex.filter(new UiAuthFilter(service));
      }
    };
  }
}
