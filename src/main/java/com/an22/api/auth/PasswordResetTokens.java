package com.an22.api.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ลิงก์ตั้งรหัสผ่าน (E41, ตาราง platform) — ส่วนที่ขั้นอื่นใช้ก่อนขั้น 11. */
@Service
public class PasswordResetTokens {

  private final JdbcClient jdbc;

  PasswordResetTokens(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /** ลบ token ของผู้ใช้ก่อนลบผู้ใช้ (FK RESTRICT) — transaction ของ platformTx แยก (ลบของผู้ใช้ที่กำลังถูกลบ ไม่มีผลถ้าการลบล้ม). */
  @Transactional("platformTx")
  public void removeFor(long tenantId, long userId) {
    jdbc.sql("DELETE FROM dt_password_reset_tokens WHERE company_id = :t AND user_id = :u")
        .param("t", tenantId)
        .param("u", userId)
        .update();
  }
}
