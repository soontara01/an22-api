package com.an22.api.platform;

import com.an22.api.model.BillingInvoice;
import com.an22.api.model.CompanyDeletion;
import com.an22.api.model.PlatformCompany;
import com.an22.api.model.Subscription;
import com.an22.api.rules.PlanRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** บริษัททั้งแพลตฟอร์ม (E36) — platformJdbc (BYPASSRLS): ทุก query ระบุบริษัทเอง. */
@Repository
public class PlatformCompanyDao {

  /** แถวบริษัท + ข้อมูลที่หน้า platform ใช้ (ชื่อของบริษัทที่ลบแล้ว = deleted_name). */
  public record CompanyRecord(
      long id, String code, String name, String taxId, String planId, boolean active, String suspendedReason, String suspendNote,
      Subscription subscription, Instant deletedAt, CompanyDeletion deletion, PlanRules.Usage usage) {}

  private static final String SELECT =
      "SELECT c.id, c.code, coalesce(i.name, c.deleted_name, '') AS name, coalesce(i.tax_id, '') AS tax_id, c.plan_id, c.active,"
          + " c.suspended_reason, coalesce(c.suspend_note, '') AS suspend_note, c.trial_ends_on, c.next_invoice_on, c.deleted_at,"
          + " c.deletion_requested_at, c.deletion_requested_by, c.deletion_reason, c.purge_after,"
          + " (SELECT count(*) FROM dt_branches b WHERE b.tenant_id = c.id AND b.active) AS branches,"
          + " (SELECT count(*) FROM dt_users u WHERE u.tenant_id = c.id AND u.active) AS users,"
          + " (SELECT count(*) FROM dt_pos_terminals t WHERE t.tenant_id = c.id AND t.active) AS terminals"
          + " FROM dt_companies c LEFT JOIN dt_company_info i ON i.tenant_id = c.id";

  private static final String INVOICES =
      "SELECT id, company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status, paid_at"
          + " FROM dt_billing_invoices";

  private final JdbcClient jdbc;

  PlatformCompanyDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  public List<CompanyRecord> list() {
    return jdbc.sql(SELECT + " ORDER BY c.id").query(PlatformCompanyDao::company).list();
  }

  public Optional<CompanyRecord> find(long id) {
    return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(PlatformCompanyDao::company).optional();
  }

  /** ล็อกแถวบริษัทจน commit (งานของ platform / billing / owner ทำทีละ transaction). */
  public boolean lock(long id) {
    return jdbc.sql("SELECT id FROM dt_companies WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional().isPresent();
  }

  /** ใบแจ้งหนี้ทุกบริษัท เรียงตาม id. */
  public List<BillingInvoice> invoices() {
    return jdbc.sql(INVOICES + " ORDER BY id").query(PlatformCompanyDao::invoice).list();
  }

  public List<BillingInvoice> invoicesOf(long companyId) {
    return jdbc.sql(INVOICES + " WHERE company_id = :c ORDER BY id").param("c", companyId).query(PlatformCompanyDao::invoice).list();
  }

  public Optional<BillingInvoice> lockInvoice(long id) {
    return jdbc.sql(INVOICES + " WHERE id = :id FOR UPDATE").param("id", id).query(PlatformCompanyDao::invoice).optional();
  }

  public void payInvoice(long id, Instant at) {
    jdbc.sql("UPDATE dt_billing_invoices SET status = 'paid', paid_at = :at WHERE id = :id")
        .param("at", Timestamp.from(at)).param("id", id).update();
  }

  /** owner ของบริษัท (ใช้งานอยู่หรือไม่ก็ได้). */
  public Optional<PlatformCompany.Owner> owner(long companyId) {
    return jdbc.sql("SELECT id, name, username, email FROM dt_users WHERE tenant_id = :t AND role = 'owner'")
        .param("t", companyId)
        .query((rs, i) -> new PlatformCompany.Owner(rs.getLong("id"), rs.getString("name"), rs.getString("username"), rs.getString("email")))
        .optional();
  }

  public void setOwnerPassword(long companyId, long userId, String hash) {
    jdbc.sql("UPDATE dt_users SET password_hash = :h WHERE tenant_id = :t AND id = :id")
        .param("h", hash).param("t", companyId).param("id", userId).update();
  }

  public void setPlan(long companyId, String planId) {
    jdbc.sql("UPDATE dt_companies SET plan_id = :p WHERE id = :id").param("p", planId).param("id", companyId).update();
  }

  /** {@code reason} null = ใช้งาน (note ว่าง). */
  public void setStatus(long companyId, boolean active, String reason, String note) {
    jdbc.sql("UPDATE dt_companies SET active = :a, suspended_reason = :r, suspend_note = :n WHERE id = :id")
        .param("a", active).param("r", reason).param("n", note).param("id", companyId).update();
  }

  /**
   * ลบข้อมูลทุกตารางของบริษัทถาวร (E39): ทุกตารางที่มี {@code tenant_id} + ลิงก์ตั้งรหัสผ่าน — ลูกก่อนแม่ตาม FK ใน catalog; คงแถว
   * dt_companies ({@code deleted_at} + ชื่อ) และใบแจ้งหนี้ค่าบริการ. คืนจำนวนแถวที่ลบ.
   */
  public int purge(long companyId, Instant at) {
    String name = jdbc.sql("SELECT name FROM dt_company_info WHERE tenant_id = :t").param("t", companyId).query(String.class)
        .optional().orElse("");
    List<String> tables = new ArrayList<>(jdbc.sql(
            "SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id'"
                + " AND table_name LIKE 'dt\\_%' ORDER BY table_name")
        .query(String.class).list());
    tables.add("dt_password_reset_tokens");
    Map<String, Set<String>> parents = new HashMap<>();
    jdbc.sql("SELECT conrelid::regclass::text AS child, confrelid::regclass::text AS parent FROM pg_constraint"
            + " WHERE contype = 'f' AND connamespace = 'public'::regnamespace AND conrelid <> confrelid")
        .query((ResultSet rs) -> {
          parents.computeIfAbsent(rs.getString("child"), k -> new LinkedHashSet<>()).add(rs.getString("parent"));
        });
    int deleted = 0;
    for (String table : childrenFirst(tables, parents)) {
      String column = "dt_password_reset_tokens".equals(table) ? "company_id" : "tenant_id";
      deleted += jdbc.sql("DELETE FROM " + table + " WHERE " + column + " = :t").param("t", companyId).update();
    }
    jdbc.sql("UPDATE dt_companies SET deleted_at = :at, deleted_name = :name, active = false, suspended_reason = 'deletion' WHERE id = :id")
        .param("at", Timestamp.from(at)).param("name", name).param("id", companyId).update();
    return deleted;
  }

  /** ตารางที่ไม่มีลูก (ในชุดที่จะลบ) เหลืออยู่ออกก่อน. */
  static List<String> childrenFirst(List<String> tables, Map<String, Set<String>> parents) {
    List<String> left = new ArrayList<>(tables);
    List<String> order = new ArrayList<>();
    while (!left.isEmpty()) {
      String next = left.stream()
          .filter(t -> left.stream().noneMatch(other -> !other.equals(t) && parents.getOrDefault(other, Set.of()).contains(t)))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("FK วนระหว่างตาราง: " + left));
      if (!next.matches("dt_[a-z0-9_]+")) {
        throw new IllegalStateException("ชื่อตารางไม่ถูกต้อง: " + next);
      }
      order.add(next);
      left.remove(next);
    }
    return order;
  }

  private static CompanyRecord company(ResultSet rs, int i) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    Timestamp requested = rs.getTimestamp("deletion_requested_at");
    CompanyDeletion deletion = requested == null ? null : new CompanyDeletion(requested.toInstant(), rs.getString("deletion_requested_by"),
        rs.getString("deletion_reason"), rs.getObject("purge_after", LocalDate.class));
    return new CompanyRecord(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("tax_id"), rs.getString("plan_id"),
        rs.getBoolean("active"), rs.getString("suspended_reason"), rs.getString("suspend_note"),
        new Subscription(rs.getObject("trial_ends_on", LocalDate.class), rs.getObject("next_invoice_on", LocalDate.class)),
        deleted == null ? null : deleted.toInstant(), deletion,
        new PlanRules.Usage(rs.getInt("branches"), rs.getInt("users"), rs.getInt("terminals")));
  }

  private static BillingInvoice invoice(ResultSet rs, int i) throws SQLException {
    Timestamp paid = rs.getTimestamp("paid_at");
    return new BillingInvoice(rs.getLong("id"), rs.getLong("company_id"), rs.getString("invoice_no"), rs.getString("plan_id"),
        rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class), rs.getBigDecimal("amount"),
        rs.getObject("issued_on", LocalDate.class), rs.getObject("due_date", LocalDate.class), rs.getString("status"),
        paid == null ? null : paid.toInstant());
  }
}
