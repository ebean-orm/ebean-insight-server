package org.ebean.monitor.ui;

import io.avaje.jsonb.Jsonb;
import io.avaje.oauth2.core.data.OidcTokens;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiAuthServiceTest {

  @Test
  void safeReturnPath_rejectsExternalAndProtocolRelativeUrls() {
    UiAuthService service = service(new FakeOidcClient());

    assertThat(service.safeReturnPath("https://evil.example/")).isEqualTo("/ux");
    assertThat(service.safeReturnPath("//evil.example/")).isEqualTo("/ux");
    assertThat(service.safeReturnPath("/ux/top?app=one")).isEqualTo("/ux/top?app=one");
  }

  @Test
  void loginCallback_validatesStateAndRotatesSession() {
    FakeOidcClient oidc = new FakeOidcClient();
    UiAuthService service = service(oidc);
    String loginUrl = service.beginLogin("/ux/top?app=one", "old-session");
    String state = query(loginUrl, "state");

    UiLoginResult result = service.completeLogin(state, "code");

    assertThat(result.returnPath()).isEqualTo("/ux/top?app=one");
    assertThat(result.session().id()).isNotEqualTo("old-session");
    assertThat(service.authenticate("old-session")).isEmpty();
    assertThat(service.authenticate(result.session().id())).isPresent();
  }

  @Test
  void loginCallback_rejectsUnknownState() {
    UiAuthService service = service(new FakeOidcClient());

    assertThatThrownBy(() -> service.completeLogin("unknown", "code"))
      .isInstanceOf(UiTokenException.class);
  }

  @Test
  void sessionCookie_isHttpOnlySecureAndLax() {
    UiAuthService service = service(new FakeOidcClient());

    var cookie = service.sessionCookie("opaque-id");

    assertThat(cookie.name()).isEqualTo("__Host-insight-ui-session");
    assertThat(cookie.value()).isEqualTo("opaque-id");
    assertThat(cookie.httpOnly()).isTrue();
    assertThat(cookie.secure()).isTrue();
    assertThat(cookie.path()).isEqualTo("/");
    assertThat(cookie.sameSite()).isEqualTo(io.avaje.jex.http.Context.Cookie.SameSite.Lax);
  }

  private static UiAuthService service(UiOidcClient oidc) {
    UiAuthSettings settings = new UiAuthSettings(true, "https://idp.example", null, null,
      "client", null, "openid", "https://insight.example/auth/callback", true,
      "__Host-insight-ui-session", false, Duration.ofHours(1), Duration.ofMinutes(5), Duration.ZERO);
    return new UiAuthService(settings, oidc, UiTokenVerifier.accepting(), new InMemoryUiSessionStore(),
      new InMemoryUiLoginTransactionStore(), Jsonb.instance());
  }

  private static String query(String url, String name) {
    String value = url.substring(url.indexOf('?') + 1);
    for (String part : value.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair[0].equals(name)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
    }
    throw new AssertionError("missing query parameter " + name);
  }

  private static final class FakeOidcClient implements UiOidcClient {
    private String nonce;
    private String state;

    @Override
    public String loginUrl(String nonce, String state, String codeChallenge) {
      this.nonce = nonce;
      this.state = state;
      return "https://idp.example/authorize?state=" + state;
    }

    @Override
    public OidcTokens obtainTokens(String code, String codeVerifier) {
      return new OidcTokens(idToken(nonce), "access", "refresh", 3600, "Bearer");
    }

    @Override
    public OidcTokens refreshAccessToken(String refreshToken) {
      return new OidcTokens(idToken(nonce), "access2", refreshToken, 3600, "Bearer");
    }

    private static String idToken(String nonce) {
      String header = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
      String payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"nonce\":\"" + nonce + "\"}").getBytes(StandardCharsets.UTF_8));
      return header + "." + payload + ".signature";
    }
  }
}
