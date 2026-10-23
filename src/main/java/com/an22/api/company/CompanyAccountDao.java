package com.an22.api.company;

import com.an22.api.model.CompanyDeletion;
import com.an22.api.model.OnboardingCounts;
import com.an22.api.rules.PlanRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * บัญชีบริษัท (E34 / E39): แถว dt_companies (แพ็กเกจ, คำขอลบ) — แก้ได้เฉพาะ platformTx (an22_app อ่านได้อย่างเดียว) จึงระบุบริษัทเอง;
 * อ่านของร้านใต้ RLS ใช้ {@code appJdbc}.
 */
@Repository
public class CompanyAccountDao {

  private static final String DELETION =
      "SELECT deletion_requested_at, deletion_requested_by, deletion_reason, purge_after FROM dt_companies WHERE id = :id";

  private final JdbcClient app;
  private final JdbcClient platform;

  CompanyAccountDao(JdbcClient appJdbc, @Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.app = appJdbc;
    this.platform = platformJdbc;
  }

  /** จำนวน record ทั้งหมดของบริษัท (RLS). */
  public OnboardingCounts onboarding() {
    return app.sql("SELECT (SELECT count(*) FROM dt_pos_terminals) AS terminals, (SELECT count(*) FROM dt_categories) AS categories,"
            + " (SELECT count(*) FROM dt_products) AS products, (SELECT count(*) FROM dt_sku_prices) AS prices,"
            + " (SELECT count(*) FROM dt_users) AS users")
        .query((rs, i) -> new OnboardingCounts(rs.getLong("terminals"), rs.getLong("categories"), rs.getLong("products"),
            rs.getLong("prices"), rs.getLong("users")))
        .single();
  }

  /** คำขอลบของบริษัทนี้ (RLS ของ dt_companies). */
  public Optional<CompanyDeletion> deletion(long companyId) {
    return app.sql(DELETION).param("id", companyId).query(CompanyAccountDao::deletionRow).list().stream()
        .filter(Objects::nonNull).findFirst();
  }

  /** ล็อกแถวบริษัท (platformTx) แล้วคืนคำขอลบที่มี (null ในแถว = ไม่มี). */
  public Optional<CompanyDeletion> lockDeletion(long companyId) {
    return platform.sql(DELETION + " FOR UPDATE").param("id", companyId).query(CompanyAccountDao::deletionRow).list().stream()
        .filter(Objects::nonNull).findFirst();
  }

  /** {@code planUsage()} ใน platformTx (ระบุบริษัทเอง — BYPASSRLS). */
  public PlanRules.Usage usageOnPlatform(long companyId) {
    return platform.sql("SELECT (SELECT count(*) FROM dt_branches WHERE tenant_id = :t AND active) AS branches,"
            + " (SELECT count(*) FROM dt_users WHERE tenant_id = :t AND active) AS users,"
            + " (SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = :t AND active) AS terminals")
        .param("t", companyId)
        .query((rs, i) -> new PlanRules.Usage(rs.getInt("branches"), rs.getInt("users"), rs.getInt("terminals")))
        .single();
  }

  public void setPlan(long companyId, String planId) {
    platform.sql("UPDATE dt_companies SET plan_id = :plan WHERE id = :id").param("plan", planId).param("id", companyId).update();
  }

  /** ขอลบ: ระงับทันที (แทนการระงับเดิม — E39). */
  public void requestDeletion(long companyId, CompanyDeletion d) {
    platform.sql("UPDATE dt_companies SET active = false, suspended_reason = 'deletion', suspend_note = NULL,"
            + " deletion_requested_at = :at, deletion_requested_by = :by, deletion_reason = :reason, purge_after = :purge WHERE id = :id")
        .param("at", Timestamp.from(d.requestedAt())).param("by", d.requestedBy()).param("reason", d.reason())
        .param("purge", d.purgeAfter()).param("id", companyId)
        .update();
  }

  /** ยกเลิกคำขอลบ: กลับมาใช้งาน (billing ตัดสินต่อ). */
  public void cancelDeletion(long companyId) {
    platform.sql("UPDATE dt_companies SET active = true, suspended_reason = NULL, deletion_requested_at = NULL,"
            + " deletion_requested_by = NULL, deletion_reason = NULL, purge_after = NULL WHERE id = :id")
        .param("id", companyId)
        .update();
  }

  private static CompanyDeletion deletionRow(ResultSet rs, int i) throws SQLException {
    Timestamp at = rs.getTimestamp("deletion_requested_at");
    if (at == null) {
      return null;
    }
    Instant requestedAt = at.toInstant();
    return new CompanyDeletion(requestedAt, rs.getString("deletion_requested_by"), rs.getString("deletion_reason"),
        rs.getObject("purge_after", LocalDate.class));
  }
}
