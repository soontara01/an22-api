package com.an22.api.rules;

import java.math.BigDecimal;
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

  /** ยาวกว่านี้ไม่ใช่ id (ตัดก่อน parse — ค่ามาจาก query ของผู้ใช้). */
  private static final int MAX_ID_TEXT = 32;
  /** {@code Number.MAX_SAFE_INTEGER} + 1 — เกินนี้ JS ไม่ถือเป็นจำนวนเต็มที่แม่น. */
  private static final BigDecimal JS_SAFE_LIMIT = BigDecimal.valueOf(9_007_199_254_740_992L);

  /** {@code Number.isInteger(Number(v))}: ตัวเลขฐานสิบ (มีทศนิยม .0 / exponent ได้) ที่เป็นจำนวนเต็ม — ไม่ใช้ regex / ไม่แปลง double. */
  private static Long id(String v) {
    String text = v == null ? "" : v.strip();
    if (text.isEmpty() || text.length() > MAX_ID_TEXT) {
      return null;
    }
    try {
      BigDecimal n = new BigDecimal(text);
      if (n.signum() != 0 && n.stripTrailingZeros().scale() > 0 || n.abs().compareTo(JS_SAFE_LIMIT) >= 0) {
        return null;
      }
      return n.longValueExact();
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }
}
