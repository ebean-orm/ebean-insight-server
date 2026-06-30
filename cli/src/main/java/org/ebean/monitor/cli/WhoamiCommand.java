package org.ebean.monitor.cli;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

import io.avaje.jsonb.Json;
import io.avaje.json.mapper.JsonMapper;
import io.avaje.oauth2.core.data.AccessToken;
import io.avaje.oauth2.core.data.JsonDataMapper;
import io.avaje.oauth2.core.jwt.SignedJwt;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** Show the identity and expiry of the cached bearer token. */
@Command(name = "whoami", mixinStandardHelpOptions = true,
    description = "Show the cached login identity and token expiry.")
final class WhoamiCommand implements Callable<Integer> {

  /** Decoded identity rendered for {@code -o json}. */
  @Json
  record Identity(
      @Nullable String subject,
      @Nullable String clientId,
      @Nullable String scope,
      @Nullable String issuer,
      @Nullable String tokenUse,
      long expiresAt,
      boolean expired) {
  }

  @Mixin OutputOptions out = new OutputOptions();

  @Override
  public Integer call() {
    Optional<TokenData> cached = TokenStore.forActiveProfile().load();
    if (cached.isEmpty()) {
      if (out.json()) {
        System.out.println("null");
      } else {
        System.out.println("Not logged in. Run `insight login`.");
      }
      return 1;
    }

    TokenData token = cached.get();
    long now = Instant.now().getEpochSecond();
    Identity identity = decode(token, now);

    if (out.json()) {
      out.printJson(Identity.class, identity);
      return 0;
    }

    System.out.printf("Subject:   %s%n", value(identity.subject()));
    System.out.printf("Client:    %s%n", value(identity.clientId()));
    System.out.printf("Scope:     %s%n", value(identity.scope()));
    System.out.printf("Issuer:    %s%n", value(identity.issuer()));
    System.out.printf("Token use: %s%n", value(identity.tokenUse()));
    System.out.printf("Expires:   %s (%s)%n",
        Instant.ofEpochSecond(identity.expiresAt()),
        identity.expired() ? "expired — run `insight login`" : "valid");
    return 0;
  }

  private static Identity decode(TokenData token, long now) {
    try {
      SignedJwt jwt = SignedJwt.parse(token.accessToken());
      JsonDataMapper mapper = JsonDataMapper.builder()
          .jsonMapper(JsonMapper.builder().build())
          .build();
      AccessToken at = mapper.readAccessToken(jwt.payload());
      long expiresAt = at.expiredAt() > 0 ? at.expiredAt() : token.expiresAt();
      return new Identity(at.sub(), at.clientId(), at.scope(), at.issuer(),
          at.tokenUse(), expiresAt, now >= expiresAt);
    } catch (RuntimeException e) {
      // token not decodable (e.g. opaque) — fall back to stored expiry only
      return new Identity(null, null, null, null, null,
          token.expiresAt(), token.isExpired(now));
    }
  }

  private static String value(@Nullable String s) {
    return s != null ? s : "(unknown)";
  }
}
