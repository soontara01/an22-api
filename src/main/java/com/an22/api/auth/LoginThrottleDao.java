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

  public boolean locked(byte[] keyHash) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_login_throttle WHERE key_hash = :k AND locked_until > now())")
        .param("k", keyHash)
        .query(Boolean.class)
        .single();
  }

  /** นับผิด 1 ครั้งในหน้าต่าง {@code windowMinutes} — ครบ {@code max} → ล็อก {@code lockMinutes} (atomic, หลาย instance ได้). */
  public void fail(byte[] keyHash, String scope, int max, int windowMinutes, int lockMinutes) {
    jdbc.sql(
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
            """)
        .param("k", keyHash)
        .param("scope", scope)
        .param("max", max)
        .param("window", windowMinutes)
        .param("lock", lockMinutes)
        .update();
  }

  public void reset(byte[] keyHash) {
    jdbc.sql("DELETE FROM dt_login_throttle WHERE key_hash = :k").param("k", keyHash).update();
  }
}
