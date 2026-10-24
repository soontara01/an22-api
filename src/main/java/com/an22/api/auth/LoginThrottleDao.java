package com.an22.api.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_login_throttle (ตาราง platform) — เวลาใช้ now() ของ DB (ทุก instance นาฬิกาเดียวกัน). */
@Repository
public class LoginThrottleDao {

  private final JdbcClient jdbc;

  LoginThrottleDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /**
   * จองสิทธิ์ลอง 1 ครั้งในหน้าต่าง {@code windowMinutes} (atomic, หลาย instance ได้ — B6): ตัวนับ +1 และครบ {@code max} → ล็อก
   * {@code lockMinutes}; ล็อกอยู่แล้ว = ไม่จอง (คืน false, ไม่นับ). สิทธิ์ที่จองนับเป็นความผิดจนกว่าจะ {@link #release}.
   */
  public boolean reserve(byte[] keyHash, String scope, int max, int windowMinutes, int lockMinutes) {
    return jdbc.sql(
            """
            INSERT INTO dt_login_throttle AS t (key_hash, scope, window_start, failures, locked_until)
            VALUES (:k, :scope, now(), 1, CASE WHEN 1 >= :max THEN now() + make_interval(mins => :lock) END)
            ON CONFLICT (key_hash) DO UPDATE SET
              window_start = CASE WHEN t.window_start <= now() - make_interval(mins => :window) THEN now() ELSE t.window_start END,
              failures = CASE WHEN t.window_start <= now() - make_interval(mins => :window) THEN 1 ELSE t.failures + 1 END,
              locked_until = CASE
                WHEN (CASE WHEN t.window_start <= now() - make_interval(mins => :window) THEN 1 ELSE t.failures + 1 END) >= :max
                THEN now() + make_interval(mins => :lock)
                ELSE t.locked_until END
              WHERE t.locked_until IS NULL OR t.locked_until <= now()
            RETURNING t.failures
            """)
        .param("k", keyHash)
        .param("scope", scope)
        .param("max", max)
        .param("window", windowMinutes)
        .param("lock", lockMinutes)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /** คืนสิทธิ์ที่จองไว้ (ลองแล้วไม่ใช่ความผิด) — ตัวนับ −1, ต่ำกว่า {@code max} → ปลดล็อก. */
  public void release(byte[] keyHash, int max) {
    jdbc.sql(
            """
            UPDATE dt_login_throttle SET failures = greatest(failures - 1, 0),
              locked_until = CASE WHEN failures - 1 < :max THEN NULL ELSE locked_until END
            WHERE key_hash = :k
            """)
        .param("k", keyHash)
        .param("max", max)
        .update();
  }

  public void reset(byte[] keyHash) {
    jdbc.sql("DELETE FROM dt_login_throttle WHERE key_hash = :k").param("k", keyHash).update();
  }
}
