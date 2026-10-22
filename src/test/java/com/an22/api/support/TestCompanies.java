package com.an22.api.support;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * สร้างบริษัทใหม่ต่อ test (รหัสสุ่ม — test ไม่ชนกัน / เปลี่ยนสถานะบริษัทได้โดยไม่กระทบ DEMO). ผู้ใช้ทุกคนรหัส {@link #PASSWORD}:
 * {@code owner} (owner), {@code boss} (admin สาขา HQ), {@code staff} (user สาขา HQ), {@code gone} (user ปิดใช้งาน);
 * สาขา HQ (ใช้งาน), BN (ใช้งาน), OLD (ปิด).
 */
@Component
public class TestCompanies {

  public static final String PASSWORD = "1234";

  public record Company(long id, String code, long hq, long bn, long old, long owner, long boss, long staff, long gone) {}

  private final TenantSql sql;
  private final String hash;

  TestCompanies(TenantSql sql, PasswordEncoder passwords) {
    this.sql = sql;
    this.hash = passwords.encode(PASSWORD);
  }

  public Company create() {
    String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    return sql.platform(jdbc -> {
      long id = jdbc.sql("INSERT INTO dt_companies (code, plan_id, next_invoice_on) VALUES (:c, 'pro', '9999-12-31') RETURNING id")
          .param("c", code).query(Long.class).single();
      jdbc.sql("INSERT INTO dt_company_info (tenant_id, name, tax_id) VALUES (:t, :n, '')").param("t", id).param("n", "บริษัท " + code).update();
      long hq = branch(jdbc, id, "HQ", "00000", true);
      long bn = branch(jdbc, id, "BN", "00001", true);
      long old = branch(jdbc, id, "OLD", "00002", false);
      long owner = user(jdbc, id, "owner", "owner", true, true, "owner@" + code.toLowerCase() + ".test");
      long boss = user(jdbc, id, "boss", "admin", false, true, "");
      long staff = user(jdbc, id, "staff", "user", false, true, "");
      long gone = user(jdbc, id, "gone", "user", false, false, "");
      for (long u : new long[] {boss, staff, gone}) {
        jdbc.sql("INSERT INTO dt_user_branches (tenant_id, user_id, branch_id) VALUES (:t, :u, :b)")
            .param("t", id).param("u", u).param("b", hq).update();
      }
      return new Company(id, code, hq, bn, old, owner, boss, staff, gone);
    });
  }

  /** เปลี่ยนสถานะบริษัท (null = ใช้งาน) — {@code deletion} ตั้ง purge_after ด้วย. */
  public void suspend(Company c, String reason) {
    sql.platform(jdbc -> jdbc.sql(
            "UPDATE dt_companies SET active = :active, suspended_reason = :reason,"
                + " purge_after = CASE WHEN :reason = 'deletion' THEN DATE '2026-12-01' END WHERE id = :id")
        .param("active", reason == null).param("reason", reason).param("id", c.id()).update());
  }

  public void setUserActive(Company c, long userId, boolean active) {
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_users SET active = :a WHERE tenant_id = :t AND id = :id")
        .param("a", active).param("t", c.id()).param("id", userId).update());
  }

  private static long branch(JdbcClient jdbc, long tenant, String code, String no, boolean active) {
    return jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address, active) VALUES (:t, :c, :c, :no, 'x', :a) RETURNING id")
        .param("t", tenant).param("c", code).param("no", no).param("a", active).query(Long.class).single();
  }

  private long user(JdbcClient jdbc, long tenant, String username, String role, boolean all, boolean active, String email) {
    return jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, email, role, active, all_branches, password_hash)"
            + " VALUES (:t, :u, :u, :e, :r, :a, :all, :h) RETURNING id")
        .param("t", tenant).param("u", username).param("e", email).param("r", role).param("a", active)
        .param("all", all).param("h", hash).query(Long.class).single();
  }
}
