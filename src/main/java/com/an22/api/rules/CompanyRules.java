package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.StoreInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** port จาก {@code core/models/company.model.ts} (E11) — ข้อความไทยตรงทุกตัวอักษร. */
public final class CompanyRules {

  public static final String HEAD_OFFICE_BRANCH_NO = "00000";
  public static final String DEFAULT_RECEIPT_FOOTER = "ขอบคุณที่ใช้บริการ";

  /** {@code COMPANY_INFO_DEFAULTS}. */
  public static final CompanyInfo COMPANY_INFO_DEFAULTS =
      new CompanyInfo(
          "", "", true, "A4", BigDecimal.valueOf(7), BigDecimal.valueOf(5), true, BigDecimal.valueOf(100),
          BigDecimal.valueOf(12), false);

  private CompanyRules() {}

  /** {@code normalizeCompanyCode()}: รหัสบริษัทไม่สนตัวพิมพ์ เก็บตัวใหญ่ (D4). */
  public static String normalizeCompanyCode(String code) {
    return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
  }

  public static boolean isHeadOffice(String branchNo) {
    return HEAD_OFFICE_BRANCH_NO.equals(branchNo);
  }

  /** {@code storeInfoOf()}: รายละเอียดที่เอกสารพิมพ์ = บริษัท + สาขาของเอกสาร. */
  public static StoreInfo storeInfoOf(CompanyInfo c, Branch branch) {
    boolean head = isHeadOffice(branch.branchNo());
    return new StoreInfo(
        c.name(), branch.name(), c.vatRegistered(), c.taxId(), head ? "head" : "branch", head ? "" : branch.branchNo(),
        branch.address(), branch.phone(), branch.receiptFooter(), c.taxInvoicePaper(), c.exchangeDays(),
        c.manualDiscountMaxPercent(), c.requireShift(), c.shiftVarianceLimit(), c.shiftMaxHours(), c.staffSeeCost());
  }

  /** {@code companyInfoOf()}: ส่วนของบริษัทใน StoreInfo. */
  public static CompanyInfo companyInfoOf(StoreInfo s) {
    return new CompanyInfo(
        s.name(), s.taxId(), s.vatRegistered(), s.taxInvoicePaper(), s.exchangeDays(), s.manualDiscountMaxPercent(),
        s.requireShift(), s.shiftVarianceLimit(), s.shiftMaxHours(), s.staffSeeCost() != null && s.staffSeeCost());
  }

  /** {@code normalizeCompanyInfo()}: ค่าที่ไม่ส่ง = ค่าเริ่ม, ตัดช่องว่าง, เลขภาษีตัด ช่องว่าง / {@code -}. */
  public static CompanyInfo normalizeCompanyInfo(CompanyInfo c) {
    CompanyInfo d = COMPANY_INFO_DEFAULTS;
    return new CompanyInfo(
        c.name() == null ? "" : c.name().strip(),
        c.taxId() == null ? "" : c.taxId().replaceAll("[\\s-]", ""),
        c.vatRegistered() == null ? d.vatRegistered() : c.vatRegistered(),
        c.taxInvoicePaper() == null ? d.taxInvoicePaper() : c.taxInvoicePaper(),
        or(c.exchangeDays(), d.exchangeDays()),
        or(c.manualDiscountMaxPercent(), d.manualDiscountMaxPercent()),
        c.requireShift() == null ? d.requireShift() : c.requireShift(),
        or(c.shiftVarianceLimit(), d.shiftVarianceLimit()),
        or(c.shiftMaxHours(), d.shiftMaxHours()),
        c.staffSeeCost() != null && c.staffSeeCost());
  }

  /** {@code companyInfoError()}: กฎเดียวของตั้งค่าบริษัท (ฟอร์ม + server). */
  public static String companyInfoError(CompanyInfo c) {
    if (c.name().isBlank()) {
      return "กรุณากรอกชื่อร้าน";
    }
    if (c.vatRegistered() && !Suppliers.isValidThaiTaxId(c.taxId())) {
      return "เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง";
    }
    if (!c.taxId().isEmpty() && !Suppliers.isValidThaiTaxId(c.taxId())) {
      return "เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง";
    }
    if (!"A4".equals(c.taxInvoicePaper()) && !"80mm".equals(c.taxInvoicePaper())) {
      return "ขนาดกระดาษใบกำกับภาษีไม่ถูกต้อง";
    }
    if (!isInteger(c.exchangeDays()) || !between(c.exchangeDays(), 0, 365)) {
      return "จำนวนวันเปลี่ยนสินค้าต้องเป็นจำนวนเต็ม 0–365";
    }
    if (!between(c.manualDiscountMaxPercent(), 0, 100)) {
      return "เพดานส่วนลดพิเศษต้องอยู่ระหว่าง 0–100%";
    }
    if (!between(c.shiftVarianceLimit(), 0, 100_000)) {
      return "เกณฑ์ส่วนต่างปิดกะต้องอยู่ระหว่าง 0–100,000 บาท";
    }
    if (!isInteger(c.shiftMaxHours()) || !between(c.shiftMaxHours(), 1, 48)) {
      return "ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48";
    }
    return null;
  }

  /** {@code branchDetailsError()}: รายละเอียดที่พิมพ์ของสาขา. */
  public static String branchDetailsError(String name, String address, String receiptFooter) {
    if (name.isBlank()) {
      return "กรุณากรอกชื่อสาขา";
    }
    if (name.length() > 100) {
      return "ชื่อสาขาต้องไม่เกิน 100 ตัวอักษร";
    }
    if (address.isBlank()) {
      return "กรุณากรอกที่อยู่";
    }
    if (receiptFooter.length() > 200) {
      return "ข้อความท้ายใบเสร็จต้องไม่เกิน 200 ตัวอักษร";
    }
    return null;
  }

  /**
   * {@code branchError()}: code A-Z / 0-9 2–10 ตัว และเลขสาขา 5 หลัก ไม่ซ้ำในบริษัท, ต้องเหลือสาขาที่ใช้งาน ≥ 1, แล้วรายละเอียดที่พิมพ์.
   *
   * @param id สาขาที่แก้ (null = ใหม่)
   * @param b ค่าหลัง normalize ครบทุก field
   */
  public static String branchError(Long id, BranchPayload b, List<Branch> branches) {
    List<Branch> others = branches.stream().filter(x -> id == null || x.id() != id).toList();
    String code = b.code().strip().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z0-9]{2,10}")) {
      return "รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว";
    }
    if (others.stream().anyMatch(x -> x.code().toUpperCase(Locale.ROOT).equals(code))) {
      return "รหัสสาขานี้มีอยู่แล้ว";
    }
    if (!b.branchNo().matches("\\d{5}")) {
      return "เลขที่สาขาต้องเป็นตัวเลข 5 หลัก (สำนักงานใหญ่ = 00000)";
    }
    if (others.stream().anyMatch(x -> x.branchNo().equals(b.branchNo()))) {
      return "เลขที่สาขานี้มีอยู่แล้ว";
    }
    if (!b.active() && others.stream().noneMatch(Branch::active)) {
      return "ต้องมีสาขาที่ใช้งานอยู่อย่างน้อย 1 สาขา";
    }
    return branchDetailsError(b.name(), b.address(), b.receiptFooter());
  }

  /** {@code normalizeBranch()} ทับค่าเดิม ({@code base}) ด้วยที่ส่งมา: ตัดช่องว่าง, code ตัวใหญ่. */
  public static BranchPayload normalizeBranch(BranchPayload base, BranchPayload in) {
    return new BranchPayload(
        in.code() != null ? in.code().strip().toUpperCase(Locale.ROOT) : base.code(),
        in.name() != null ? in.name().strip() : base.name(),
        in.branchNo() != null ? in.branchNo().strip() : base.branchNo(),
        in.address() != null ? in.address().strip() : base.address(),
        in.phone() != null ? in.phone().strip() : base.phone(),
        in.receiptFooter() != null ? in.receiptFooter().strip() : base.receiptFooter(),
        in.active() != null ? in.active() : base.active());
  }

  /** ค่าเริ่มของสาขาใหม่ ({@code BRANCH_DEFAULTS} + ช่องบังคับว่าง). */
  public static BranchPayload branchDefaults() {
    return new BranchPayload("", "", "", "", "", DEFAULT_RECEIPT_FOOTER, true);
  }

  public static BranchPayload payloadOf(Branch b) {
    return new BranchPayload(b.code(), b.name(), b.branchNo(), b.address(), b.phone(), b.receiptFooter(), b.active());
  }

  static boolean isInteger(BigDecimal value) {
    return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
  }

  static boolean between(BigDecimal value, long min, long max) {
    return value.compareTo(BigDecimal.valueOf(min)) >= 0 && value.compareTo(BigDecimal.valueOf(max)) <= 0;
  }

  private static BigDecimal or(BigDecimal value, BigDecimal fallback) {
    return value == null ? fallback : value;
  }
}
