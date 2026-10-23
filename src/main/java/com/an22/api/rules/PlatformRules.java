package com.an22.api.rules;

/** port จาก {@code core/models/platform.model.ts} (E36): งานของผู้ดูแลแพลตฟอร์มกับบริษัท / ใบแจ้งหนี้. */
public final class PlatformRules {

  private PlatformRules() {}

  /** {@code suspendCompanyError()}: บริษัทที่ใช้งานอยู่ + มีเหตุผล. */
  public static String suspendCompanyError(boolean active, boolean deleted, String note) {
    if (deleted) {
      return "บริษัทนี้ถูกลบข้อมูลแล้ว";
    }
    if (!active) {
      return "บริษัทนี้ถูกระงับอยู่แล้ว";
    }
    return note == null || note.isBlank() ? "กรุณากรอกเหตุผลที่ระงับ" : null;
  }

  /** {@code reactivateCompanyError()}: เฉพาะที่ platform ระงับ — ค้างชำระกลับด้วยการชำระ, รอลบกลับเมื่อ owner ยกเลิก. */
  public static String reactivateCompanyError(boolean active, String suspendedReason, boolean deleted) {
    if (deleted) {
      return "บริษัทนี้ถูกลบข้อมูลแล้ว";
    }
    if (active) {
      return "บริษัทนี้ใช้งานอยู่แล้ว";
    }
    if ("overdue".equals(suspendedReason)) {
      return "บริษัทค้างชำระ — บันทึกรับชำระใบแจ้งหนี้แทน";
    }
    if ("deletion".equals(suspendedReason)) {
      return "บริษัทอยู่ระหว่างรอลบข้อมูล — ให้เจ้าของบริษัทยกเลิกคำขอลบ";
    }
    return null;
  }

  /** {@code payInvoiceError()}: รับชำระ (เงินเข้านอกระบบ) ได้ครั้งเดียว. */
  public static String payInvoiceError(String status) {
    if ("paid".equals(status)) {
      return "ใบแจ้งหนี้นี้ชำระแล้ว";
    }
    return "void".equals(status) ? "ใบแจ้งหนี้นี้ถูกยกเลิกแล้ว" : null;
  }
}
