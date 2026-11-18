package com.an22.api.auth;

import com.an22.api.model.SetupLink;
import com.an22.api.rules.SignupRules;
import com.an22.api.security.PiiCipher;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ลิงก์ตั้งรหัสผ่าน (E41 / E59, ตาราง platform) — ส่วนที่ package อื่นใช้. */
@Service
public class PasswordResetTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final PiiCipher cipher;
  private final Clock clock;

  PasswordResetTokens(@Qualifier("platformJdbc") JdbcClient platformJdbc, PiiCipher cipher, Clock clock) {
    this.jdbc = platformJdbc;
    this.cipher = cipher;
    this.clock = clock;
  }

  /** ลบ token ของผู้ใช้ก่อนลบผู้ใช้ (FK RESTRICT) — transaction ของ platformTx แยก (ลบของผู้ใช้ที่กำลังถูกลบ ไม่มีผลถ้าการลบล้ม). */
  @Transactional("platformTx")
  public void removeFor(long tenantId, long userId) {
    jdbc.sql("DELETE FROM dt_password_reset_tokens WHERE company_id = :t AND user_id = :u")
        .param("t", tenantId)
        .param("u", userId)
        .update();
  }

  /**
   * ลิงก์ตั้งรหัสผ่านใหม่ของ owner (E59, {@code kind = setup}, {@code SETUP_LINK_HOURS}): ลิงก์ที่ยังไม่ใช้ของผู้ใช้คนนี้ทุกตัว (ทั้ง setup และ reset)
   * ใช้ไม่ได้ทันที — ใช้ได้เฉพาะลิงก์ล่าสุดที่ platform ส่งต่อ. เก็บแค่ HMAC ของ token; token ดิบอยู่ในคำตอบครั้งเดียว. เข้าร่วม platformTx ของผู้เรียก.
   */
  @Transactional("platformTx")
  public SetupLink issueSetup(long companyId, long userId) {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    jdbc.sql("UPDATE dt_password_reset_tokens SET used_at = :now WHERE company_id = :c AND user_id = :u AND used_at IS NULL")
        .param("now", Timestamp.from(now)).param("c", companyId).param("u", userId).update();
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = now.plus(Duration.ofHours(SignupRules.SETUP_LINK_HOURS));
    jdbc.sql("INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at, kind)"
            + " VALUES (:c, :u, :hash, :expires, 'setup')")
        .param("c", companyId).param("u", userId).param("hash", cipher.hmac(token)).param("expires", Timestamp.from(expiresAt))
        .update();
    return new SetupLink("/auth/reset-password?token=" + token, expiresAt);
  }
}
