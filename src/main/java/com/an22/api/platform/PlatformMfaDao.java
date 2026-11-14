package com.an22.api.platform;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_platform_admin_mfa (E56) ใน platformTx — secret เข้ารหัสแล้ว (service เข้า / ถอดรหัส). */
@Repository
public class PlatformMfaDao {

  public record Row(byte[] secretEnc, boolean confirmed, Long lastStep) {}

  private final JdbcClient jdbc;

  PlatformMfaDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  public Optional<Row> find(long adminId) {
    return jdbc.sql("SELECT secret_enc, confirmed_at IS NOT NULL AS confirmed, last_step FROM dt_platform_admin_mfa WHERE admin_id = :a")
        .param("a", adminId)
        .query((rs, i) -> new Row(rs.getBytes("secret_enc"), rs.getBoolean("confirmed"), (Long) rs.getObject("last_step")))
        .optional();
  }

  /** secret ใหม่ที่รอยืนยัน — ไม่ทับแถวที่ยืนยันแล้ว. */
  public void savePending(long adminId, byte[] secretEnc) {
    jdbc.sql("INSERT INTO dt_platform_admin_mfa (admin_id, secret_enc) VALUES (:a, :s)"
            + " ON CONFLICT (admin_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, last_step = NULL"
            + " WHERE dt_platform_admin_mfa.confirmed_at IS NULL")
        .param("a", adminId)
        .param("s", secretEnc)
        .update();
  }

  /** ใช้รหัสของช่วง {@code step} (atomic — ช่วงเดิมผ่านได้ครั้งเดียว); false = ใช้ไปแล้ว / ไม่มีแถว. */
  public boolean use(long adminId, long step) {
    return jdbc.sql("UPDATE dt_platform_admin_mfa SET confirmed_at = COALESCE(confirmed_at, now()), last_step = :step"
            + " WHERE admin_id = :a AND (last_step IS NULL OR last_step < :step)")
        .param("a", adminId)
        .param("step", step)
        .update() == 1;
  }
}
