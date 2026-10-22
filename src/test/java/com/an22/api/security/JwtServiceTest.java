package com.an22.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.an22.api.config.ApiProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

  private static final String K1 = "1:" + key();
  private static final String K2 = "2:" + key();
  private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);

  private static String key() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getEncoder().encodeToString(b);
  }

  private static JwtService service(String secret, Clock clock) {
    ApiProperties props =
        new ApiProperties(null, null, new ApiProperties.Jwt(secret, 7), new ApiProperties.Log(ApiProperties.LogBody.ERROR, 4096));
    return new JwtService(props, clock);
  }

  @Test
  void shopTokenCarriesTenantAndIsShopOnly() {
    JwtService jwt = service(K1, NOW);
    String token = jwt.issueShop(7, 42, "admin", false, List.of(1L, 2L));

    Jwt decoded = jwt.shopDecoder().decode(token);
    assertThat(decoded.getSubject()).isEqualTo("42");
    assertThat(decoded.<Number>getClaim("tenantId").longValue()).isEqualTo(7);
    assertThat(decoded.getAudience()).containsExactly("shop");
    assertThat(decoded.getExpiresAt()).isEqualTo(NOW.instant().plus(Duration.ofDays(7)));
    assertThat(decoded.getHeaders()).containsEntry("kid", "1");
    assertThatThrownBy(() -> jwt.platformDecoder().decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void platformTokenIsPlatformOnly() {
    JwtService jwt = service(K1, NOW);
    String token = jwt.issuePlatform(3);
    assertThat(jwt.platformDecoder().decode(token).getSubject()).isEqualTo("3");
    assertThat(jwt.decodeShop(token)).isEmpty();
  }

  @Test
  void rotatingKeepsOldTokensAndSignsWithTheNewestKid() {
    String old = service(K1, NOW).issueShop(1, 1, "owner", true, List.of());
    JwtService rotated = service(K1 + "," + K2, NOW);

    assertThat(rotated.decodeShop(old)).isPresent();
    String fresh = rotated.issueShop(1, 1, "owner", true, List.of());
    assertThat(rotated.shopDecoder().decode(fresh).getHeaders()).containsEntry("kid", "2");
    assertThat(service(K2, NOW).decodeShop(old)).as("kid เก่าถูกลบแล้ว").isEmpty();
  }

  @Test
  void expiredTamperedOrForeignTokensFail() {
    JwtService jwt = service(K1, NOW);
    String expired = service(K1, Clock.offset(NOW, Duration.ofDays(-8))).issueShop(1, 1, "owner", true, List.of());
    assertThat(jwt.decodeShop(expired)).isEmpty();

    String token = jwt.issueShop(1, 1, "owner", true, List.of());
    String[] parts = token.split("\\.");
    String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"tenantId\":1", "\"tenantId\":2").getBytes());
    assertThat(jwt.decodeShop(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();

    String otherSecret = service("1:" + key(), NOW).issueShop(1, 1, "owner", true, List.of());
    assertThat(jwt.decodeShop(otherSecret)).isEmpty();
    assertThat(jwt.decodeShop("garbage")).isEmpty();
    assertThat(jwt.decodeShop(null)).isEmpty();
  }

  @Test
  void badSecretRefusesWithoutLeakingIt() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> service("", NOW)).hasMessageContaining("JWT_SECRET");
    assertThatThrownBy(() -> service("1:" + shortKey, NOW)).hasMessageContaining("32 byte").hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> service("abc:" + key(), NOW)).hasMessageContaining("ตัวเลข");
    assertThatThrownBy(() -> service(K1 + "," + K1, NOW)).hasMessageContaining("ซ้ำ");
  }
}
