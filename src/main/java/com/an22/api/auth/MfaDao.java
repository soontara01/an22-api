package com.an22.api.auth;

import com.an22.api.auth.SessionDao.Via;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * dt_user_mfa (E55) — SQL ระบุ tenant เองทุกคำสั่ง ใช้ได้ทั้ง appTx (RLS กรองซ้ำ) และ platformTx (ขั้นรหัสของ login ก่อนมี session):
 * {@code Via} ต้องตรงกับ transaction ของ service. secret เข้ารหัสแล้ว (service เข้า / ถอดรหัส).
 */
@Repository
public class MfaDao {

  /** แถวของผู้ใช้: {@code confirmed} = เปิดใช้แล้ว, {@code lastStep} = ช่วงที่ใช้ล่าสุด (null = ยังไม่เคย). */
  public record Row(byte[] secretEnc, boolean confirmed, Long lastStep) {}

  private final JdbcClient app;
  private final JdbcClient platform;

  MfaDao(JdbcClient appJdbc, @Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.app = appJdbc;
    this.platform = platformJdbc;
  }

  private JdbcClient jdbc(Via via) {
    return via == Via.APP ? app : platform;
  }

  public Optional<Row> find(Via via, long tenantId, long userId) {
    return jdbc(via).sql("SELECT secret_enc, confirmed_at IS NOT NULL AS confirmed, last_step FROM dt_user_mfa"
            + " WHERE tenant_id = :t AND user_id = :u")
        .param("t", tenantId)
        .param("u", userId)
        .query((rs, i) -> new Row(rs.getBytes("secret_enc"), rs.getBoolean("confirmed"), (Long) rs.getObject("last_step")))
        .optional();
  }

  /** secret ใหม่ที่รอยืนยัน — ไม่ทับแถวที่ยืนยันแล้ว (คืน false). */
  public boolean savePending(Via via, long tenantId, long userId, byte[] secretEnc) {
    return jdbc(via).sql("INSERT INTO dt_user_mfa (tenant_id, user_id, secret_enc) VALUES (:t, :u, :s)"
            + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, last_step = NULL"
            + " WHERE dt_user_mfa.confirmed_at IS NULL")
        .param("t", tenantId)
        .param("u", userId)
        .param("s", secretEnc)
        .update() == 1;
  }

  /**
   * ใช้รหัสของช่วง {@code step}: เปิดใช้ (ถ้ายังไม่เปิด) + จำช่วงล่าสุด — atomic กันรหัสเดียวกันผ่าน 2 request พร้อมกัน.
   *
   * @return false = มี request อื่นใช้ช่วงนี้ (หรือหลังจากนี้) ไปแล้ว / แถวหายไป
   */
  public boolean use(Via via, long tenantId, long userId, long step) {
    return jdbc(via).sql("UPDATE dt_user_mfa SET confirmed_at = COALESCE(confirmed_at, now()), last_step = :step"
            + " WHERE tenant_id = :t AND user_id = :u AND (last_step IS NULL OR last_step < :step)")
        .param("t", tenantId)
        .param("u", userId)
        .param("step", step)
        .update() == 1;
  }

  public boolean delete(Via via, long tenantId, long userId) {
    return jdbc(via).sql("DELETE FROM dt_user_mfa WHERE tenant_id = :t AND user_id = :u")
        .param("t", tenantId)
        .param("u", userId)
        .update() == 1;
  }
}
