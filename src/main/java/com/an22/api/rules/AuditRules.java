package com.an22.api.rules;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** port จาก {@code core/models/audit.model.ts}: ตัวกรองของ {@code GET /audit-log} ({@code parseAuditQuery()}) — ใครเห็นอะไรอยู่ใน SQL ของ dao. */
public final class AuditRules {

  /** {@code AuditAction} ทั้งหมด (= CHECK ของ dt_audit_log). */
  public static final Set<String> ACTIONS = Set.of("login", "login_failed", "sale_void", "manual_discount", "credit_note",
      "tax_invoice_reissue", "stock_out", "serial_remove", "transfer_cancel", "price_change", "promotion_change", "user_change",
      "user_password", "owner_transfer", "plan_change", "signup", "company_suspend", "company_reactivate", "invoice_paid",
      "company_export", "deletion_request", "deletion_cancel", "password_reset", "menu_access", "company_settings", "branch_settings",
      "branch_change", "terminal_change", "shift_close_other", "shift_approve", "shift_adjust");

  private AuditRules() {}

  /** {@code AuditQuery}: null = ไม่กรอง. */
  public record Query(LocalDate from, LocalDate to, String action, Long userId, Long branchId) {}

  /** {@code parseAuditQuery()}: ค่าที่อ่านไม่ได้ = ไม่กรอง (ช่วงวันที่ยังต้องครบ — ListLimits). */
  public static Query parseAuditQuery(String from, String to, String action, String userId, String branchId) {
    return new Query(date(from), date(to), action != null && ACTIONS.contains(action) ? action : null, id(userId), id(branchId));
  }

  private static LocalDate date(String v) {
    if (v == null || !v.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return null;
    }
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** {@code Number.isInteger(Number(v))}. */
  private static Long id(String v) {
    if (v == null || !v.strip().matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
      return null;
    }
    try {
      double n = Double.parseDouble(v.strip());
      return n == Math.rint(n) && Math.abs(n) < 9.007199254740992E15 ? (long) n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
