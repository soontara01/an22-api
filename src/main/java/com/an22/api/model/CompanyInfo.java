package com.an22.api.model;

import java.math.BigDecimal;

/**
 * {@code CompanyInfo} (company.model.ts, E11) — ตัวเลขเป็น BigDecimal เพื่อให้กฎตรวจ "จำนวนเต็ม" ได้แบบ {@code Number.isInteger()}
 * (ตอบ JSON เป็นตัวเลขไม่มีทศนิยมเกิน).
 */
public record CompanyInfo(
    String name,
    String taxId,
    Boolean vatRegistered,
    String taxInvoicePaper,
    BigDecimal exchangeDays,
    BigDecimal manualDiscountMaxPercent,
    Boolean requireShift,
    BigDecimal shiftVarianceLimit,
    BigDecimal shiftMaxHours,
    Boolean staffSeeCost,
    /** E55: none / admins / all — null (ไม่ส่ง) = ค่าเริ่ม {@code none} หลัง normalize. */
    String mfaRequired) {

  /** ไม่มี {@code mfaRequired} (StoreInfo, signup, test เดิม) = {@code none}. */
  public CompanyInfo(
      String name,
      String taxId,
      Boolean vatRegistered,
      String taxInvoicePaper,
      BigDecimal exchangeDays,
      BigDecimal manualDiscountMaxPercent,
      Boolean requireShift,
      BigDecimal shiftVarianceLimit,
      BigDecimal shiftMaxHours,
      Boolean staffSeeCost) {
    this(name, taxId, vatRegistered, taxInvoicePaper, exchangeDays, manualDiscountMaxPercent, requireShift, shiftVarianceLimit,
        shiftMaxHours, staffSeeCost, "none");
  }

  /** ค่าเดิมทุกช่อง เปลี่ยนแค่ {@code mfaRequired}. */
  public CompanyInfo withMfaRequired(String value) {
    return new CompanyInfo(name, taxId, vatRegistered, taxInvoicePaper, exchangeDays, manualDiscountMaxPercent, requireShift,
        shiftVarianceLimit, shiftMaxHours, staffSeeCost, value);
  }
}
