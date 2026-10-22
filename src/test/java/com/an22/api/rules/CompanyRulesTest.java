package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.StoreInfo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code company.model.spec.ts} + {@code store-info.model.spec.ts}. */
class CompanyRulesTest {

  private static final Branch HQ = new Branch(1, "HQ", "สำนักงานใหญ่", "00000", "99/9 พญาไท", "", "ขอบคุณที่ใช้บริการ", true);

  private static BranchPayload b(String code, String name, String no, String address, String footer, boolean active) {
    return new BranchPayload(code, name, no, address, "", footer, active);
  }

  private static CompanyInfo company(String name, String taxId) {
    var d = CompanyRules.COMPANY_INFO_DEFAULTS;
    return new CompanyInfo(name, taxId, d.vatRegistered(), d.taxInvoicePaper(), d.exchangeDays(), d.manualDiscountMaxPercent(),
        d.requireShift(), d.shiftVarianceLimit(), d.shiftMaxHours(), d.staffSeeCost());
  }

  @Test
  void knowsTheHeadOfficeAndChecksTaxIds() {
    assertThat(CompanyRules.isHeadOffice("00000")).isTrue();
    assertThat(CompanyRules.isHeadOffice("00001")).isFalse();
    assertThat(Suppliers.isValidThaiTaxId("0105550123451")).isTrue();
    assertThat(Suppliers.isValidThaiTaxId("0105550123452")).isFalse();
    assertThat(Suppliers.isValidThaiTaxId("123")).isFalse();
  }

  @Test
  void checksABranch() {
    List<Branch> branches = List.of(HQ);
    BranchPayload ok = b("BKK01", "สาขาบางนา", "00001", "บางนา", "ขอบคุณที่ใช้บริการ", true);
    assertThat(CompanyRules.branchError(null, ok, branches)).isNull();
    assertThat(CompanyRules.branchError(null, b("bkk-1", "x", "00001", "x", "", true), branches))
        .isEqualTo("รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว");
    assertThat(CompanyRules.branchError(null, b("hq", "x", "00001", "x", "", true), branches)).isEqualTo("รหัสสาขานี้มีอยู่แล้ว");
    assertThat(CompanyRules.branchError(null, b("BKK01", "x", "1", "x", "", true), branches))
        .isEqualTo("เลขที่สาขาต้องเป็นตัวเลข 5 หลัก (สำนักงานใหญ่ = 00000)");
    assertThat(CompanyRules.branchError(null, b("BKK01", "x", "00000", "x", "", true), branches)).isEqualTo("เลขที่สาขานี้มีอยู่แล้ว");
    assertThat(CompanyRules.branchError(null, b("BKK01", " ", "00001", "x", "", true), branches)).isEqualTo("กรุณากรอกชื่อสาขา");
    assertThat(CompanyRules.branchError(null, b("BKK01", "x", "00001", "", "", true), branches)).isEqualTo("กรุณากรอกที่อยู่");
    assertThat(CompanyRules.branchError(null, b("BKK01", "x", "00001", "x", "x".repeat(201), true), branches))
        .isEqualTo("ข้อความท้ายใบเสร็จต้องไม่เกิน 200 ตัวอักษร");
    // แก้ตัวเองไม่ใช่ซ้ำ แต่สาขาสุดท้ายที่ใช้งานปิดไม่ได้
    assertThat(CompanyRules.branchError(1L, CompanyRules.payloadOf(HQ), branches)).isNull();
    assertThat(CompanyRules.branchError(1L, b("HQ", "สำนักงานใหญ่", "00000", "x", "", false), branches))
        .isEqualTo("ต้องมีสาขาที่ใช้งานอยู่อย่างน้อย 1 สาขา");
  }

  @Test
  void checksCompanySettings() {
    CompanyInfo ok = company("ร้าน", "0105550123451");
    assertThat(CompanyRules.companyInfoError(ok)).isNull();
    assertThat(CompanyRules.companyInfoError(company(" ", "0105550123451"))).isEqualTo("กรุณากรอกชื่อร้าน");
    assertThat(CompanyRules.companyInfoError(company("ร้าน", "1"))).isEqualTo("เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง");
    CompanyInfo zeroHours = new CompanyInfo("ร้าน", "0105550123451", true, "A4", BigDecimal.valueOf(7), BigDecimal.valueOf(5), true,
        BigDecimal.valueOf(100), BigDecimal.ZERO, false);
    assertThat(CompanyRules.companyInfoError(zeroHours)).isEqualTo("ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48");
  }

  @Test
  void normalizesCompanySettingsLikeTheForm() {
    CompanyInfo n = CompanyRules.normalizeCompanyInfo(
        new CompanyInfo(" A ", "0-1055-50123-45-1", null, null, null, null, null, null, null, null));
    assertThat(n).isEqualTo(new CompanyInfo("A", "0105550123451", true, "A4", BigDecimal.valueOf(7), BigDecimal.valueOf(5), true,
        BigDecimal.valueOf(100), BigDecimal.valueOf(12), false));
  }

  @Test
  void putsStoreDetailsTogetherFromCompanyAndBranch() {
    CompanyInfo c = company("บริษัท ก จำกัด", "0105550123451");
    StoreInfo info = CompanyRules.storeInfoOf(c, HQ);
    assertThat(info.name()).isEqualTo("บริษัท ก จำกัด");
    assertThat(info.placeName()).isEqualTo("สำนักงานใหญ่");
    assertThat(info.branchType()).isEqualTo("head");
    assertThat(info.branchNo()).isEmpty();
    assertThat(info.address()).isEqualTo("99/9 พญาไท");
    StoreInfo branch = CompanyRules.storeInfoOf(c, new Branch(2, "BN", "x", "00001", "a", "", "", true));
    assertThat(branch.branchType()).isEqualTo("branch");
    assertThat(branch.branchNo()).isEqualTo("00001");
    assertThat(CompanyRules.companyInfoOf(info)).isEqualTo(c);
  }

  private static StoreInfo store(String name, String taxId, boolean vat, String branchType, String branchNo, String address,
      String paper, BigDecimal variance, BigDecimal hours) {
    return new StoreInfo(name, "", vat, taxId, branchType, branchNo, address, "", "ขอบคุณที่ใช้บริการ", paper,
        BigDecimal.valueOf(7), BigDecimal.valueOf(5), true, variance, hours, false);
  }

  @Test
  void validatesAWholeStoreInfo() {
    BigDecimal v = BigDecimal.valueOf(100);
    BigDecimal h = BigDecimal.valueOf(12);
    assertThat(StoreInfoRules.storeInfoError(store("ร้านทดสอบ", "0105550123451", true, "head", "", "กรุงเทพฯ", "A4", v, h))).isNull();
    assertThat(StoreInfoRules.storeInfoError(store(" ", "0105550123451", true, "head", "", "กรุงเทพฯ", "A4", v, h))).isEqualTo("กรุณากรอกชื่อร้าน");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123452", true, "head", "", "กรุงเทพฯ", "A4", v, h)))
        .isEqualTo("เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "", false, "head", "", "กรุงเทพฯ", "A4", v, h))).isNull();
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "123", false, "head", "", "กรุงเทพฯ", "A4", v, h)))
        .isEqualTo("เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "branch", "12", "กรุงเทพฯ", "A4", v, h)))
        .isEqualTo("เลขที่สาขาต้องเป็นตัวเลข 5 หลัก");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "branch", "00012", "กรุงเทพฯ", "A4", v, h))).isNull();
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "head", "", "", "A4", v, h))).isEqualTo("กรุณากรอกที่อยู่");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "head", "", "x", "A5", v, h)))
        .isEqualTo("ขนาดกระดาษใบกำกับภาษีไม่ถูกต้อง");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "head", "", "x", "A4", BigDecimal.valueOf(-1), h)))
        .isEqualTo("เกณฑ์ส่วนต่างปิดกะต้องอยู่ระหว่าง 0–100,000 บาท");
    assertThat(StoreInfoRules.storeInfoError(store("ร้าน", "0105550123451", true, "head", "", "x", "A4", v, new BigDecimal("8.5"))))
        .isEqualTo("ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48");
  }

  @Test
  void normalizesStoreInfoAndDropsTheHeadOfficeBranchNumber() {
    StoreInfo n = StoreInfoRules.normalizeStoreInfo(new StoreInfo(" A ", null, true, "0-1055-50123-45-1", "head", "00001", "x", "", "f",
        "A4", null, null, null, null, null, null));
    assertThat(n.name()).isEqualTo("A");
    assertThat(n.taxId()).isEqualTo("0105550123451");
    assertThat(n.branchNo()).isEmpty();
    assertThat(n.requireShift()).isTrue();
    assertThat(n.shiftVarianceLimit()).isEqualByComparingTo("100");
    assertThat(n.shiftMaxHours()).isEqualByComparingTo("12");
  }
}
