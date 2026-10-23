package com.an22.api.billing;

import com.an22.api.model.BillingInvoice;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** การเก็บเงิน SaaS (ตาราง platform: dt_companies, dt_billing_invoices, dt_platform_doc_counters) — ใน transaction ของ platformTx. */
@Repository
public class BillingDao {

  /** บริษัทที่ล็อกไว้ (FOR UPDATE) สำหรับคิดรอบ. */
  public record CompanyRow(long id, String planId, LocalDate trialEndsOn, LocalDate nextInvoiceOn, boolean active, String suspendedReason) {}

  private final JdbcClient jdbc;

  BillingDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /** ล็อกแถวบริษัทจน commit — คิดรอบของบริษัทเดียวกันทีละ transaction (login / me / job พร้อมกัน). ลบแล้ว = empty. */
  public Optional<CompanyRow> lock(long companyId) {
    return jdbc.sql("SELECT id, plan_id, trial_ends_on, next_invoice_on, active, suspended_reason FROM dt_companies"
            + " WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
        .param("id", companyId)
        .query((rs, i) -> new CompanyRow(rs.getLong("id"), rs.getString("plan_id"), rs.getObject("trial_ends_on", LocalDate.class),
            rs.getObject("next_invoice_on", LocalDate.class), rs.getBoolean("active"), rs.getString("suspended_reason")))
        .optional();
  }

  /** บริษัทที่ยังไม่ถูกลบ (job). */
  public List<Long> companyIds() {
    return jdbc.sql("SELECT id FROM dt_companies WHERE deleted_at IS NULL ORDER BY id").query(Long.class).list();
  }

  /** ล็อกของ job ทั้งแพลตฟอร์ม (ไม่ได้ = instance อื่นกำลังทำ). */
  public boolean tryJobLock(long jobId) {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(:job)").param("job", jobId).query(Boolean.class).single();
  }

  /** เลขถัดไปของ prefix ทั้งแพลตฟอร์ม ({@code SUB-YYYYMM-}). */
  public int nextNumber(String prefix) {
    return jdbc.sql("INSERT INTO dt_platform_doc_counters (prefix, last_no) VALUES (:prefix, 1)"
            + " ON CONFLICT (prefix) DO UPDATE SET last_no = dt_platform_doc_counters.last_no + 1 RETURNING last_no")
        .param("prefix", prefix).query(Integer.class).single();
  }

  /** ใบแจ้งหนี้ของรอบ — มีอยู่แล้ว (unique company + period_start) = ไม่ทำอะไร. */
  public void insertInvoice(long companyId, String invoiceNo, String planId, LocalDate start, LocalDate end, BigDecimal amount,
      LocalDate dueDate) {
    jdbc.sql("INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date,"
            + " status) VALUES (:company, :no, :plan, :start, :end, :amount, :start, :due, 'open')"
            + " ON CONFLICT (company_id, period_start) DO NOTHING")
        .param("company", companyId).param("no", invoiceNo).param("plan", planId).param("start", start).param("end", end)
        .param("amount", amount).param("due", dueDate)
        .update();
  }

  public boolean invoiced(long companyId, LocalDate periodStart) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_billing_invoices WHERE company_id = :company AND period_start = :start)")
        .param("company", companyId).param("start", periodStart).query(Boolean.class).single();
  }

  public List<BillingInvoice> invoices(long companyId) {
    return jdbc.sql("SELECT id, company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status, paid_at"
            + " FROM dt_billing_invoices WHERE company_id = :company ORDER BY id")
        .param("company", companyId)
        .query((rs, i) -> {
          Timestamp paid = rs.getTimestamp("paid_at");
          return new BillingInvoice(rs.getLong("id"), rs.getLong("company_id"), rs.getString("invoice_no"), rs.getString("plan_id"),
              rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class), rs.getBigDecimal("amount"),
              rs.getObject("issued_on", LocalDate.class), rs.getObject("due_date", LocalDate.class), rs.getString("status"),
              paid == null ? null : paid.toInstant());
        })
        .list();
  }

  public void setNextInvoiceOn(long companyId, LocalDate next) {
    jdbc.sql("UPDATE dt_companies SET next_invoice_on = :next WHERE id = :id").param("next", next).param("id", companyId).update();
  }

  public void suspendOverdue(long companyId) {
    jdbc.sql("UPDATE dt_companies SET active = false, suspended_reason = 'overdue' WHERE id = :id").param("id", companyId).update();
  }

  /** ล้าง token ตั้งรหัสผ่านที่หมดอายุ / ใช้แล้วเกิน 1 วัน และตัวนับ login ผิดที่หมดช่วงแล้ว. */
  public int cleanUp() {
    int tokens = jdbc.sql("DELETE FROM dt_password_reset_tokens WHERE expires_at < now() - interval '1 day'").update();
    int counters = jdbc.sql("DELETE FROM dt_login_throttle WHERE greatest(window_start, coalesce(locked_until, window_start)) < now() - interval '1 day'").update();
    return tokens + counters;
  }
}
