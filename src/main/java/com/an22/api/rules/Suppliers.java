package com.an22.api.rules;

import com.an22.api.model.Supplier;
import java.util.List;

/** port จาก {@code core/models/supplier.model.ts} + กฎของ {@code handleSuppliers()} ใน mock. */
public final class Suppliers {

  private static final String EMAIL = "[^@\\s]+@[^@\\s]+\\.[^@\\s]+";

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

  /** {@code normalize()} ของ mock: ค่าที่ไม่ส่ง = {@code SUPPLIER_DEFAULTS}, ชื่อตัดช่องว่าง, สำนักงานใหญ่ไม่มีเลขสาขา. */
  public static Supplier normalize(Supplier p) {
    String branchType = p.branchType() == null ? "head" : p.branchType();
    return new Supplier(
        null,
        or(p.code(), ""),
        p.name() == null ? "" : p.name().strip(),
        or(p.taxId(), ""),
        branchType,
        "head".equals(branchType) ? "" : or(p.branchNo(), ""),
        or(p.address(), ""),
        or(p.contactName(), ""),
        or(p.phone(), ""),
        or(p.email(), ""),
        p.creditDays() == null ? 30 : p.creditDays(),
        or(p.bankName(), ""),
        or(p.bankAccountNo(), ""),
        or(p.bankAccountName(), ""),
        or(p.note(), ""),
        p.active() == null || p.active(),
        0);
  }

  /** {@code validate()} ของ mock. {@code exceptId} = ผู้จำหน่ายที่แก้. */
  public static String supplierError(Supplier p, List<Supplier> suppliers, Long exceptId) {
    if (!p.code().matches("[A-Z0-9-]+")) {
      return "รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -";
    }
    if (p.name().isBlank()) {
      return "กรุณากรอกชื่อผู้จำหน่าย";
    }
    if (!isValidThaiTaxId(p.taxId())) {
      return "เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง";
    }
    if ("branch".equals(p.branchType()) && !p.branchNo().matches("\\d{5}")) {
      return "เลขสาขาต้องเป็นตัวเลข 5 หลัก";
    }
    if (!p.email().isEmpty() && !p.email().matches(EMAIL)) {
      return "อีเมลไม่ถูกต้อง";
    }
    if (p.creditDays() < 0) {
      return "เครดิตเทอมต้องไม่ติดลบ";
    }
    List<Supplier> others = suppliers.stream().filter(s -> exceptId == null || !s.id().equals(exceptId)).toList();
    if (others.stream().anyMatch(s -> s.code().equals(p.code()))) {
      return "รหัส " + p.code() + " ซ้ำ";
    }
    if (others.stream().anyMatch(s -> s.taxId().equals(p.taxId()) && s.branchType().equals(p.branchType())
        && s.branchNo().equals(p.branchNo()))) {
      return "เลขผู้เสียภาษีและสาขานี้มีอยู่แล้ว";
    }
    return null;
  }

  private static String or(String value, String fallback) {
    return value == null ? fallback : value;
  }
}
