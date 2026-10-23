package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ตาม {@code platform.model.spec.ts} + {@code purgeError} ของ {@code company-data.model.spec.ts}. */
class PlatformRulesTest {

  @Test
  void suspendsAnActiveCompanyWithAReason() {
    assertThat(PlatformRules.suspendCompanyError(true, false, " ")).isEqualTo("กรุณากรอกเหตุผลที่ระงับ");
    assertThat(PlatformRules.suspendCompanyError(false, false, "x")).isEqualTo("บริษัทนี้ถูกระงับอยู่แล้ว");
    assertThat(PlatformRules.suspendCompanyError(true, false, "ผิดเงื่อนไข")).isNull();
    assertThat(PlatformRules.suspendCompanyError(true, true, "x")).isEqualTo("บริษัทนี้ถูกลบข้อมูลแล้ว");
  }

  @Test
  void turnsBackOnOnlyWhatThePlatformSuspended() {
    assertThat(PlatformRules.reactivateCompanyError(false, "platform", false)).isNull();
    assertThat(PlatformRules.reactivateCompanyError(false, "overdue", false)).isEqualTo("บริษัทค้างชำระ — บันทึกรับชำระใบแจ้งหนี้แทน");
    assertThat(PlatformRules.reactivateCompanyError(false, "deletion", false)).contains("รอลบข้อมูล");
    assertThat(PlatformRules.reactivateCompanyError(true, null, false)).isEqualTo("บริษัทนี้ใช้งานอยู่แล้ว");
  }

  @Test
  void marksAnOpenInvoicePaidOnce() {
    assertThat(PlatformRules.payInvoiceError("open")).isNull();
    assertThat(PlatformRules.payInvoiceError("paid")).isEqualTo("ใบแจ้งหนี้นี้ชำระแล้ว");
    assertThat(PlatformRules.payInvoiceError("void")).isEqualTo("ใบแจ้งหนี้นี้ถูกยกเลิกแล้ว");
  }

  @Test
  void letsThePlatformDeleteOnlyADueRequest() {
    LocalDate purgeAfter = LocalDate.parse("2026-11-17");
    assertThat(CompanyDataRules.purgeError(null, LocalDate.parse("2026-12-01"))).isEqualTo("บริษัทนี้ไม่ได้ขอลบข้อมูล");
    assertThat(CompanyDataRules.purgeError(purgeAfter, LocalDate.parse("2026-11-16"))).isEqualTo("ลบข้อมูลได้ตั้งแต่วันที่ 17/11/2569");
    assertThat(CompanyDataRules.purgeError(purgeAfter, purgeAfter)).isNull();
  }
}
