package com.an22.api.auth;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** ลิงก์ตั้งรหัสผ่าน (E41 / E59) — platformTx (ยังไม่มี session); อ่าน / แก้ระบุบริษัทเองเสมอ (BYPASSRLS). */
@Repository
class AccountDao {

  /** ลิงก์ที่ล็อกไว้ (FOR UPDATE — ใช้ได้ครั้งเดียวแม้กดพร้อมกัน); {@code kind} reset (E41) / setup (E59). */
  record ResetLink(long id, long companyId, long userId, Instant expiresAt, boolean used, String kind) {}

  record Owner(long id, String email) {}

  private final JdbcClient jdbc;

  AccountDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /** owner ที่ใช้งานอยู่ของบริษัทที่อีเมลตรง (ไม่สนตัวพิมพ์). */
  Optional<Owner> activeOwnerByEmail(long companyId, String emailLower) {
    return jdbc.sql("SELECT id, email FROM dt_users WHERE tenant_id = :t AND role = 'owner' AND active AND lower(email) = :email")
        .param("t", companyId).param("email", emailLower)
        .query((rs, i) -> new Owner(rs.getLong("id"), rs.getString("email")))
        .optional();
  }

  void insertResetToken(long companyId, long userId, byte[] tokenHash, Instant expiresAt) {
    jdbc.sql("INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at) VALUES (:c, :u, :hash, :expires)")
        .param("c", companyId).param("u", userId).param("hash", tokenHash).param("expires", Timestamp.from(expiresAt))
        .update();
  }

  Optional<ResetLink> lockResetToken(byte[] tokenHash) {
    return jdbc.sql("SELECT id, company_id, user_id, expires_at, used_at IS NOT NULL AS used, kind FROM dt_password_reset_tokens"
            + " WHERE token_hash = :hash FOR UPDATE")
        .param("hash", tokenHash)
        .query((rs, i) -> new ResetLink(rs.getLong("id"), rs.getLong("company_id"), rs.getLong("user_id"),
            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("used"), rs.getString("kind")))
        .optional();
  }

  void useResetToken(long id, Instant at) {
    jdbc.sql("UPDATE dt_password_reset_tokens SET used_at = :at WHERE id = :id").param("at", Timestamp.from(at)).param("id", id).update();
  }

  void setPassword(long companyId, long userId, String passwordHash) {
    jdbc.sql("UPDATE dt_users SET password_hash = :hash WHERE tenant_id = :t AND id = :id")
        .param("hash", passwordHash).param("t", companyId).param("id", userId)
        .update();
  }
}
