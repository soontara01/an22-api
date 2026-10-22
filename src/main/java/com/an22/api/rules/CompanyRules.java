package com.an22.api.rules;

import java.util.Locale;

/** port จาก {@code core/models/company.model.ts} (ส่วนที่ขั้น 2 ใช้ — ที่เหลือมาในขั้น 3). */
public final class CompanyRules {

  private CompanyRules() {}

  /** {@code normalizeCompanyCode()}: รหัสบริษัทไม่สนตัวพิมพ์ เก็บตัวใหญ่ (D4). */
  public static String normalizeCompanyCode(String code) {
    return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
  }
}
