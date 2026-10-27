package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.SignupPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code signup.model.spec.ts}, {@code company-data.model.spec.ts} และ {@code parseAuditQuery} ของ {@code audit.model.spec.ts}. */
class SignupAndAuditRulesTest {

  private static SignupPayload valid() {
    return new SignupPayload("acme", "บริษัท แอคมี จำกัด", "0105550123451", true,
        new BranchPayload("HQ", "สำนักงานใหญ่", "00000", "กรุงเทพฯ", "", null, null),
        new SignupPayload.Owner("สมศักดิ์", "boss", "boss@acme.test", "secret"), "starter");
  }

  private static String error(SignupPayload p) {
    return SignupRules.signupError(SignupRules.normalize(p));
  }

  private static SignupPayload withOwner(String name, String email, String password) {
    SignupPayload v = valid();
    return new SignupPayload(v.companyCode(), v.companyName(), v.taxId(), v.vatRegistered(), v.branch(),
        new SignupPayload.Owner(name, "boss", email, password), v.planId());
  }

  @Test
  void checksTheCompanyCodeFormat() {
    assertThat(SignupRules.companyCodeError(" acme ")).isNull();
    assertThat(SignupRules.companyCodeError("")).isEqualTo("กรุณากรอกรหัสบริษัท");
    assertThat(SignupRules.companyCodeError("AB")).isEqualTo("รหัสบริษัทใช้ได้เฉพาะ A-Z, 0-9 ยาว 3–20 ตัว");
    assertThat(SignupRules.companyCodeError("AC-ME")).isNotNull();
  }

  @Test
  void checksCompanyFirstBranchOwnerAndPackageInThatOrder() {
    SignupPayload v = valid();
    assertThat(error(v)).isNull();
    assertThat(error(new SignupPayload(v.companyCode(), " ", v.taxId(), true, v.branch(), v.owner(), v.planId())))
        .isEqualTo("กรุณากรอกชื่อบริษัท");
    assertThat(error(new SignupPayload(v.companyCode(), v.companyName(), "123", true, v.branch(), v.owner(), v.planId())))
        .isEqualTo("เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง");
    assertThat(error(new SignupPayload(v.companyCode(), v.companyName(), "", false, v.branch(), v.owner(), v.planId()))).isNull();
    assertThat(error(new SignupPayload(v.companyCode(), v.companyName(), v.taxId(), true,
        new BranchPayload("HQ", "สำนักงานใหญ่", "1", "กรุงเทพฯ", "", null, null), v.owner(), v.planId()))).isNotNull();
    assertThat(error(withOwner("", "boss@acme.test", "secret"))).isEqualTo("กรุณากรอกชื่อเจ้าของบริษัท");
    assertThat(error(withOwner("สมศักดิ์", "", "secret")))
        .isEqualTo("เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)");
    assertThat(error(withOwner("สมศักดิ์", "boss@acme.test", "12"))).isEqualTo("รหัสผ่านต้องมีอย่างน้อย 4 ตัว");
    assertThat(error(new SignupPayload(v.companyCode(), v.companyName(), v.taxId(), true, v.branch(), v.owner(), "gold")))
        .isEqualTo("กรุณาเลือกแพ็กเกจ");
    // ไม่ส่งอะไรเลย = ค่าว่าง (ไม่ใช่ error 500)
    assertThat(error(new SignupPayload(null, null, null, null, null, null, null))).isEqualTo("กรุณากรอกรหัสบริษัท");
  }

  @Test
  void asksForTheCompanyCodeTypedBackAndAPassword() {
    assertThat(CompanyDataRules.deletionRequestError(" demo ", "x", "DEMO")).isNull();
    assertThat(CompanyDataRules.deletionRequestError("DEM", "x", "DEMO")).isEqualTo("พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน");
    assertThat(CompanyDataRules.deletionRequestError(" demo ", "", "DEMO")).isEqualTo("กรุณากรอกรหัสผ่านเพื่อยืนยัน");
    assertThat(CompanyDataRules.deletionRequestError(null, null, "DEMO")).isEqualTo("พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน");
  }

  @Test
  void readsTheAuditQueryIgnoringOddValues() {
    assertThat(AuditRules.parseAuditQuery("2026-10-01", "1/10/2026", "sale_void", "2", "x"))
        .isEqualTo(new AuditRules.Query(LocalDate.parse("2026-10-01"), null, "sale_void", 2L, null));
    assertThat(AuditRules.parseAuditQuery(null, null, "drop_table", null, null).action()).isNull();
    assertThat(AuditRules.parseAuditQuery("2026-02-30", null, null, "2.0", "1d").from()).isNull();
    assertThat(AuditRules.parseAuditQuery(null, null, null, "2.0", "1d"))
        .isEqualTo(new AuditRules.Query(null, null, null, 2L, null));
    assertThat(AuditRules.ACTIONS).hasSize(31);
    // แบบ Number(): exponent / ทศนิยม .0 / เครื่องหมาย ได้; ไม่ใช่จำนวนเต็ม / ยาวเกิน / เกิน MAX_SAFE_INTEGER = ไม่กรอง
    assertThat(AuditRules.parseAuditQuery(null, null, null, " +1e2 ", "-0").userId()).isEqualTo(100L);
    assertThat(AuditRules.parseAuditQuery(null, null, null, null, "-0").branchId()).isZero();
    for (String odd : new String[] {"1.5", "1e-1", "9007199254740992", "1e999999999", "1".repeat(33), "1".repeat(5000) + "x", "0x10", "NaN"}) {
      assertThat(AuditRules.parseAuditQuery(null, null, null, odd, null).userId()).as(odd).isNull();
    }
    assertThat(AuditRules.parseAuditQuery(null, null, null, "9007199254740991", null).userId()).isEqualTo(9_007_199_254_740_991L);
  }

  @Test
  void auditDetailsReadLikeTheFrontEnd() {
    // ตาม "audit details (C3-5)" ของ audit.model.spec.ts
    assertThat(AuditRules.auditBaht(BigDecimal.valueOf(1490))).isEqualTo("฿1,490");
    assertThat(AuditRules.auditBaht(new BigDecimal("50.00"))).isEqualTo("฿50");
    List<Branch> branches = List.of(branch(1, "HQ"), branch(3, "BN"));
    assertThat(AuditRules.auditBranchCodes(List.of(3L, 1L), branches)).isEqualTo("BN, HQ");
    assertThat(AuditRules.auditBranchCodes(List.of(9L), branches)).isEqualTo("#9");

    CompanyInfo before = CompanyRules.COMPANY_INFO_DEFAULTS;
    CompanyInfo seeCost = new CompanyInfo(before.name(), before.taxId(), before.vatRegistered(), before.taxInvoicePaper(),
        before.exchangeDays(), new BigDecimal("5.00"), before.requireShift(), before.shiftVarianceLimit(), before.shiftMaxHours(), true);
    assertThat(AuditRules.companySettingsChanges(before, seeCost)).isEqualTo("ผู้ใช้ทั่วไปเห็นทุน / กำไร: ปิด → เปิด");
    CompanyInfo more = new CompanyInfo(before.name(), before.taxId(), false, "80mm", BigDecimal.ZERO,
        before.manualDiscountMaxPercent(), before.requireShift(), BigDecimal.valueOf(1500), before.shiftMaxHours(), before.staffSeeCost());
    assertThat(AuditRules.companySettingsChanges(before, more)).isEqualTo(
        "จด VAT: จด → ไม่จด · กระดาษใบกำกับภาษี: A4 → 80 มม. · เปลี่ยนสินค้าได้ภายใน: 7 วัน → ไม่จำกัด"
            + " · ส่วนต่างที่ต้องให้ admin อนุมัติ: ฿100 → ฿1,500");
    assertThat(AuditRules.companySettingsChanges(before, before)).isEqualTo("บันทึกโดยไม่มีการเปลี่ยนแปลง");
  }

  private static Branch branch(long id, String code) {
    return new Branch(id, code, code, "00000", "", "", "", true);
  }
}
