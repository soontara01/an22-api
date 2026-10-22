package com.an22.api.company;

import com.an22.api.auth.ShopSession;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.PlanRules.LimitKind;
import com.an22.api.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * เพดานแพ็กเกจ (E34): เพิ่ม record ที่ใช้งาน หรือเปิดใช้งานกลับ เกินแพ็กเกจ → 403 ({@code limitError()} ของ mock) — ตรวจหลังกฎอื่นของ
 * endpoint ผ่านแล้ว; record ที่ใช้งานอยู่แล้วไม่นับเป็นการเพิ่ม. เรียกใน transaction ของ service หลัง {@code TenantLocks.lock(ACCOUNTS)}.
 */
@Component
public class PlanLimits {

  private final JdbcClient jdbc;

  PlanLimits(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public void check(ShopSession s, LimitKind kind, boolean active, boolean wasActive) {
    if (!active || wasActive) {
      return;
    }
    String problem = PlanRules.planLimitError(kind, PlanRules.planOf(s.planId()), usage());
    if (problem != null) {
      throw ApiException.forbidden(problem);
    }
  }

  /** {@code planUsage()}: นับเฉพาะที่ใช้งาน. */
  public PlanRules.Usage usage() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM dt_branches WHERE active) AS branches,"
                + " (SELECT count(*) FROM dt_users WHERE active) AS users,"
                + " (SELECT count(*) FROM dt_pos_terminals WHERE active) AS terminals")
        .query((rs, i) -> new PlanRules.Usage(rs.getInt("branches"), rs.getInt("users"), rs.getInt("terminals")))
        .single();
  }
}
