package org.ebean.monitor.ui;

import io.avaje.jsonb.Jsonb;
import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import io.avaje.oauth2.core.data.OidcTokens;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiAuthFilterTest {

  @Test
  void htmlRequest_redirectsToLogin() {
    UiAuthService service = service();
    AtomicReference<String> redirect = new AtomicReference<>();
    UiAuthFilter filter = new UiAuthFilter(service);

    filter.filter(context("/ux/top", null, null, redirect), () -> {
    });

    assertThat(redirect.get()).startsWith("/auth/login?return=");
  }

  @Test
  void chartDataRequest_returns401() {
    UiAuthFilter filter = new UiAuthFilter(service());

    assertThatThrownBy(() -> filter.filter(context("/ux/top/data", null, null, new AtomicReference<>()),
      () -> {
      }))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void validSession_proceeds() {
    InMemoryUiSessionStore store = new InMemoryUiSessionStore();
    UiAuthService service = service(store);
    store.save(new UiSession("opaque", "access", "refresh", null,
      Instant.now().plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1))));
    AtomicReference<Boolean> proceeded = new AtomicReference<>(false);

    new UiAuthFilter(service).filter(context("/ux", "opaque", null, new AtomicReference<>()),
      () -> proceeded.set(true));

    assertThat(proceeded).hasValue(true);
  }

  private static UiAuthService service() {
    return service(new InMemoryUiSessionStore());
  }

  private static UiAuthService service(InMemoryUiSessionStore store) {
    UiAuthSettings settings = new UiAuthSettings(true, "https://idp.example", null, null,
      "client", null, "openid", "https://insight.example/auth/callback", true,
      "__Host-insight-ui-session", false, Duration.ofHours(1), Duration.ofMinutes(5), Duration.ZERO);
    return new UiAuthService(settings, new FakeOidcClient(), UiTokenVerifier.accepting(), store,
      new InMemoryUiLoginTransactionStore(), Jsonb.instance());
  }

  private static Context context(String path, String cookie, String query,
                                 AtomicReference<String> redirect) {
    InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
      case "path" -> path;
      case "cookie" -> cookie;
      case "queryString" -> query;
      case "redirect" -> {
        redirect.set((String) args[0]);
        yield null;
      }
      case "toString" -> "UiAuthTestContext";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> throw new UnsupportedOperationException(method.getName());
    };
    return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(),
      new Class<?>[]{Context.class}, handler);
  }

  private static final class FakeOidcClient implements UiOidcClient {
    @Override public String loginUrl(String nonce, String state, String codeChallenge) {
      return "https://idp.example";
    }
    @Override public OidcTokens obtainTokens(String code, String codeVerifier) {
      return new OidcTokens(null, "access", "refresh", 3600, "Bearer");
    }
    @Override public OidcTokens refreshAccessToken(String refreshToken) {
      return new OidcTokens(null, "access", refreshToken, 3600, "Bearer");
    }
  }
}
