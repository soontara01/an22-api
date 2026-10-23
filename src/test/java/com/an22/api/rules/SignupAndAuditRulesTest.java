package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.BranchPayload;
import com.an22.api.model.SignupPayload;
import java.time.LocalDate;
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
  }
}
