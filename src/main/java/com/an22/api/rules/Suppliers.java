package com.an22.api.rules;

/** port จาก {@code core/models/supplier.model.ts} (ส่วนที่ใช้ก่อนขั้น 4). */
public final class Suppliers {

  private Suppliers() {}

  /** เลขประจำตัวผู้เสียภาษี 13 หลัก + check digit (mod 11). */
  public static boolean isValidThaiTaxId(String taxId) {
    if (taxId == null || !taxId.matches("\\d{13}")) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      sum += (taxId.charAt(i) - '0') * (13 - i);
    }
    return (11 - (sum % 11)) % 10 == taxId.charAt(12) - '0';
  }
}
