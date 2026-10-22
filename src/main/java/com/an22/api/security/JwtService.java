package com.an22.api.security;

import com.an22.api.config.ApiProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * ออก / ตรวจ JWT (HS256) ของ api — stateless: ตรวจได้ทุก instance ที่มี {@code JWT_SECRET} ชุดเดียวกัน.
 *
 * <ul>
 *   <li>{@code JWT_SECRET} = {@code <kid ตัวเลข>:<base64 ≥ 32 byte>,…}: เซ็นด้วย kid สูงสุด (header {@code kid}), ตรวจได้ทุก kid ที่ตั้ง
 *   <li>ร้าน vs platform แยกด้วย {@code aud} ({@code shop} / {@code platform}) — ใช้สลับกัน → ไม่ผ่าน (401)
 *   <li>claims ร้าน: {@code sub} (userId), {@code tenantId}, {@code role}, {@code allBranches} / {@code branchIds} — สิทธิ์จริงอ่านจาก DB ทุก
 *       request; tenant มาจาก token เท่านั้น
 * </ul>
 */
@Service
public class JwtService {

  public static final String ISSUER = "an22-api";
  public static final String AUD_SHOP = "shop";
  public static final String AUD_PLATFORM = "platform";
  public static final String CLAIM_TENANT = "tenantId";

  private final NimbusJwtEncoder encoder;
  private final String signingKid;
  private final JwtDecoder shopDecoder;
  private final JwtDecoder platformDecoder;
  private final Duration ttl;
  private final Clock clock;

  public JwtService(ApiProperties props, Clock clock) {
    TreeMap<Integer, byte[]> secrets = parse(props.jwt().secret());
    List<JWK> keys = new ArrayList<>();
    secrets.forEach((kid, secret) -> keys.add(new OctetSequenceKey.Builder(secret).keyID(kid.toString()).build()));
    JWKSet all = new JWKSet(keys);
    this.signingKid = secrets.lastKey().toString();
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(all.getKeyByKeyId(signingKid))));
    this.shopDecoder = decoder(all, AUD_SHOP, clock);
    this.platformDecoder = decoder(all, AUD_PLATFORM, clock);
    this.ttl = Duration.ofDays(props.jwt().ttlDays());
    this.clock = clock;
  }

  /** token ของผู้ใช้ร้าน. */
  public String issueShop(long tenantId, long userId, String role, boolean allBranches, List<Long> branchIds) {
    return encode(
        AUD_SHOP,
        Long.toString(userId),
        Map.of(CLAIM_TENANT, tenantId, "role", role, "allBranches", allBranches, "branchIds", branchIds));
  }

  /** token ของ platform admin. */
  public String issuePlatform(long adminId) {
    return encode(AUD_PLATFORM, Long.toString(adminId), Map.of());
  }

  public JwtDecoder shopDecoder() {
    return shopDecoder;
  }

  public JwtDecoder platformDecoder() {
    return platformDecoder;
  }

  /** ตรวจ token ร้าน — ไม่ผ่าน (ปลอม / หมดอายุ / ของ platform / ไม่มี) = empty. */
  public Optional<Jwt> decodeShop(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(shopDecoder.decode(token));
    } catch (JwtException e) {
      return Optional.empty();
    }
  }

  private String encode(String audience, String subject, Map<String, Object> claims) {
    Instant now = clock.instant();
    JwtClaimsSet.Builder set =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .audience(List.of(audience))
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString());
    claims.forEach(set::claim);
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(signingKid).build();
    return encoder.encode(JwtEncoderParameters.from(header, set.build())).getTokenValue();
  }

  private static JwtDecoder decoder(JWKSet keys, String audience, Clock clock) {
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, new ImmutableJWKSet<>(keys)));
    // เวลา / issuer / aud ตรวจด้วย validator ของ Spring ข้างล่าง (ใช้ Clock เดียวกับแอป)
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestamps.setClock(clock);
    OAuth2TokenValidator<Jwt> aud =
        jwt -> jwt.getAudience() != null && jwt.getAudience().equals(List.of(audience))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience", null));
    OAuth2TokenValidator<Jwt> required =
        jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "claims", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER), aud, required));
    return decoder;
  }

  /** {@code <kid>:<base64>,…} → kid (ตัวเลข) → secret — error บอกชื่อตัวแปร ไม่บอกค่า. */
  static TreeMap<Integer, byte[]> parse(String env) {
    if (env == null || env.isBlank()) {
      throw new IllegalStateException("ต้องตั้ง JWT_SECRET (<kid>:<base64 ≥ 32 byte>,…)");
    }
    TreeMap<Integer, byte[]> secrets = new TreeMap<>();
    for (String entry : env.split(",")) {
      String[] parts = entry.trim().split(":", 2);
      if (parts.length != 2 || !parts[0].trim().matches("[1-9][0-9]{0,3}")) {
        throw new IllegalStateException("JWT_SECRET ต้องเป็น <kid>:<base64 ≥ 32 byte> คั่นด้วย , (kid = ตัวเลข 1–9999)");
      }
      int kid = Integer.parseInt(parts[0].trim());
      byte[] secret;
      try {
        secret = Base64.getDecoder().decode(parts[1].trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("JWT_SECRET ต้องเป็น base64");
      }
      if (secret.length < 32) {
        throw new IllegalStateException("JWT_SECRET: secret ของ kid " + kid + " ต้องยาว ≥ 32 byte");
      }
      if (secrets.put(kid, secret) != null) {
        throw new IllegalStateException("JWT_SECRET: kid " + kid + " ซ้ำ");
      }
    }
    return secrets;
  }
}
