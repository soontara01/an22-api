package com.an22.api.rules;

import com.an22.api.model.StoreInfo;
import java.math.BigDecimal;

/** port จาก {@code core/models/store-info.model.ts} (ช่วงเปลี่ยนผ่าน {@code PUT /settings/store}, E11). */
public final class StoreInfoRules {

  private StoreInfoRules() {}

  /** {@code storeInfoError()}: กฎของบริษัท แล้วรายละเอียดสาขา — ข้อความเดียวกับฟอร์มตั้งค่า. */
  public static String storeInfoError(StoreInfo s) {
    if (s.placeName().length() > 100) {
      return "ชื่อสถานประกอบการต้องไม่เกิน 100 ตัวอักษร";
    }
    if ("branch".equals(s.branchType()) && !s.branchNo().matches("\\d{5}")) {
      return "เลขที่สาขาต้องเป็นตัวเลข 5 หลัก";
    }
    String company = CompanyRules.companyInfoError(CompanyRules.companyInfoOf(s));
    if (company != null) {
      return company;
    }
    return CompanyRules.branchDetailsError(
        s.placeName().isEmpty() ? s.name() : s.placeName(), s.address(), s.receiptFooter());
  }

  /** {@code normalizeStoreInfo()}: ค่าที่ไม่ส่ง = ค่าเริ่ม, ตัดช่องว่าง, สำนักงานใหญ่ไม่มีเลขสาขา. */
  public static StoreInfo normalizeStoreInfo(StoreInfo s) {
    var d = CompanyRules.COMPANY_INFO_DEFAULTS;
    String branchType = s.branchType() == null ? "head" : s.branchType();
    return new StoreInfo(
        text(s.name()),
        text(s.placeName()),
        s.vatRegistered() == null ? d.vatRegistered() : s.vatRegistered(),
        s.taxId() == null ? "" : s.taxId().replaceAll("[\\s-]", ""),
        branchType,
        "branch".equals(branchType) ? text(s.branchNo()) : "",
        text(s.address()),
        text(s.phone()),
        s.receiptFooter() == null ? CompanyRules.DEFAULT_RECEIPT_FOOTER : s.receiptFooter().strip(),
        s.taxInvoicePaper() == null ? d.taxInvoicePaper() : s.taxInvoicePaper(),
        or(s.exchangeDays(), d.exchangeDays()),
        or(s.manualDiscountMaxPercent(), d.manualDiscountMaxPercent()),
        s.requireShift() == null ? d.requireShift() : s.requireShift(),
        or(s.shiftVarianceLimit(), d.shiftVarianceLimit()),
        or(s.shiftMaxHours(), d.shiftMaxHours()),
        s.staffSeeCost() != null && s.staffSeeCost());
  }

  private static String text(String value) {
    return value == null ? "" : value.strip();
  }

  private static BigDecimal or(BigDecimal value, BigDecimal fallback) {
    return value == null ? fallback : value;
  }
}
