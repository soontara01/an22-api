package com.an22.api.rules;

/** port จาก {@code core/models/company-data.model.ts} (E38 / E39): export + ขอลบข้อมูลบริษัท. */
public final class CompanyDataRules {

  public static final int EXPORT_SCHEMA_VERSION = 1;
  /** วันระหว่างขอลบกับวันที่ platform ลบได้. */
  public static final int DELETION_WAIT_DAYS = 30;

  private CompanyDataRules() {}

  /** {@code deletionRequestError()}: พิมพ์รหัสบริษัทให้ตรง + กรอกรหัสผ่าน (ตัวรหัสผ่าน server ตรวจ). */
  public static String deletionRequestError(String typedCode, String password, String companyCode) {
    if (!CompanyRules.normalizeCompanyCode(typedCode).equals(CompanyRules.normalizeCompanyCode(companyCode))) {
      return "พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน";
    }
    return password == null || password.isEmpty() ? "กรุณากรอกรหัสผ่านเพื่อยืนยัน" : null;
  }
}
