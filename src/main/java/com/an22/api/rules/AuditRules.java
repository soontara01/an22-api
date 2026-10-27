package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.CompanyInfo;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * port จาก {@code core/models/audit.model.ts}: ตัวกรองของ {@code GET /audit-log} ({@code parseAuditQuery()}) — ใครเห็นอะไรอยู่ใน SQL ของ dao —
 * และข้อความ detail ({@code auditBaht()}, {@code auditBranchCodes()}, {@code companySettingsChanges()} — C3-5).
 */
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

  /** {@code auditBaht()}: "฿1,490" — เงินใน detail มีตัวคั่นหลักพัน. */
  public static String auditBaht(BigDecimal amount) {
    return "฿" + new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(amount);
  }

  public static String auditBaht(double amount) {
    return auditBaht(BigDecimal.valueOf(amount));
  }

  /** {@code auditBranchCodes()}: "HQ, BN" — สาขาของผู้ใช้เป็นรหัส ไม่ใช่ id; id ที่ไม่รู้จัก = "#id". */
  public static String auditBranchCodes(Collection<Long> ids, List<Branch> branches) {
    return ids.stream()
        .map(id -> branches.stream().filter(b -> b.id() == id).map(Branch::code).findFirst().orElse("#" + id))
        .collect(Collectors.joining(", "));
  }

  private record Setting(String label, Function<CompanyInfo, Object> value, Function<Object, String> text) {}

  private static String onOff(Object v) {
    return Boolean.TRUE.equals(v) ? "เปิด" : "ปิด";
  }

  private static String textOrDash(Object v) {
    return v == null || v.toString().isEmpty() ? "-" : v.toString();
  }

  private static String plain(Object v) {
    return v instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString() : String.valueOf(v);
  }

  /** ป้ายตามหน้าตั้งค่า + วิธีอ่านค่า — ลำดับเดียวกับ {@code COMPANY_SETTING_TEXT}. */
  private static final List<Setting> COMPANY_SETTINGS = List.of(
      new Setting("ชื่อผู้ประกอบการ", CompanyInfo::name, AuditRules::textOrDash),
      new Setting("เลขประจำตัวผู้เสียภาษี", CompanyInfo::taxId, AuditRules::textOrDash),
      new Setting("จด VAT", CompanyInfo::vatRegistered, v -> Boolean.TRUE.equals(v) ? "จด" : "ไม่จด"),
      new Setting("กระดาษใบกำกับภาษี", CompanyInfo::taxInvoicePaper, v -> "80mm".equals(v) ? "80 มม." : plain(v)),
      new Setting("เปลี่ยนสินค้าได้ภายใน", CompanyInfo::exchangeDays,
          v -> v instanceof BigDecimal n && n.signum() == 0 ? "ไม่จำกัด" : plain(v) + " วัน"),
      new Setting("ส่วนลดพิเศษที่พนักงานให้ได้สูงสุด", CompanyInfo::manualDiscountMaxPercent, v -> plain(v) + "%"),
      new Setting("ใช้ระบบกะ", CompanyInfo::requireShift, AuditRules::onOff),
      new Setting("ส่วนต่างที่ต้องให้ admin อนุมัติ", CompanyInfo::shiftVarianceLimit, v -> auditBaht((BigDecimal) v)),
      new Setting("เตือนเมื่อกะเปิดนานเกิน", CompanyInfo::shiftMaxHours, v -> plain(v) + " ชม."),
      new Setting("ผู้ใช้ทั่วไปเห็นทุน / กำไร", CompanyInfo::staffSeeCost, AuditRules::onOff));

  private static boolean same(Object a, Object b) {
    return a instanceof BigDecimal x && b instanceof BigDecimal y ? x.compareTo(y) == 0 : Objects.equals(a, b);
  }

  /** {@code companySettingsChanges()}: เฉพาะค่าที่เปลี่ยน "ป้าย: เดิม → ใหม่" คั่นด้วย " · ". */
  public static String companySettingsChanges(CompanyInfo before, CompanyInfo after) {
    List<String> changes = new ArrayList<>();
    for (Setting s : COMPANY_SETTINGS) {
      Object a = s.value().apply(before);
      Object b = s.value().apply(after);
      if (!same(a, b)) {
        changes.add(s.label() + ": " + s.text().apply(a) + " → " + s.text().apply(b));
      }
    }
    return changes.isEmpty() ? "บันทึกโดยไม่มีการเปลี่ยนแปลง" : String.join(" · ", changes);
  }
}
