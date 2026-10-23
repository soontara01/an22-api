package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.TaxInvoice;
import com.an22.api.model.TaxInvoiceBuyer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code sale.model.spec.ts} + {@code tax-invoice.model.spec.ts} (ส่วนที่ server ใช้). */
class SaleAndTaxInvoiceRulesTest {

  /** เวลาท้องถิ่น (Asia/Bangkok) วันที่ {@code day} ต.ค. 2026. */
  private static Instant at(int hour, int day) {
    return LocalDateTime.of(2026, 10, day, hour, 30).atZone(com.an22.api.config.ClockConfig.BANGKOK).toInstant();
  }

  private static final List<Branch> BRANCHES =
      List.of(new Branch(1, "HQ", "", "00000", "", "", "", true), new Branch(2, "BN", "", "00001", "", "", "", true));

  @Test
  void takesTheLocalCalendarDayOfASale() {
    assertThat(SaleRules.saleDay(at(0, 4))).isEqualTo("2026-10-04");
    assertThat(SaleRules.saleDay(at(23, 4))).isEqualTo("2026-10-04");
  }

  @Test
  void voidsAPaidBillOnTheDayOfSaleOnlyWithAReason() {
    Instant date = at(9, 4);
    assertThat(SaleRules.voidError("paid", date, null, "ลูกค้าเปลี่ยนใจ", "2026-10-04", null, BRANCHES)).isNull();
    assertThat(SaleRules.voidError("paid", date, null, "  ", "2026-10-04", null, BRANCHES)).isEqualTo("กรุณาระบุเหตุผลการยกเลิก");
    assertThat(SaleRules.voidError("paid", date, null, "คืน", "2026-10-05", null, BRANCHES))
        .isEqualTo("ยกเลิกได้เฉพาะบิลของวันนี้ บิลข้ามวันต้องออกใบลดหนี้");
    assertThat(SaleRules.voidError("cancelled", date, null, "x", "2026-10-04", null, BRANCHES)).isEqualTo("ยกเลิกได้เฉพาะบิลที่ชำระแล้ว");
  }

  @Test
  void voidsOnlyAtTheBranchItWasSoldAt() {
    Instant date = at(9, 4);
    assertThat(SaleRules.branchMismatchError(1L, 1L, BRANCHES, "ยกเลิก")).isNull();
    assertThat(SaleRules.voidError("paid", date, 1L, "x", "2026-10-04", 2L, BRANCHES)).isEqualTo("บิลนี้ขายที่สาขา HQ — ยกเลิกได้ที่สาขานั้น");
    assertThat(SaleRules.branchMismatchError(1L, 2L, List.of(), "คืน")).isEqualTo("บิลนี้ขายที่สาขาอื่น — คืนได้ที่สาขานั้น");
    assertThat(SaleRules.voidError("paid", date, null, "x", "2026-10-04", 2L, BRANCHES)).isNull();
    assertThat(SaleRules.voidError("paid", date, 1L, "x", "2026-10-04", null, BRANCHES)).isNull();
  }

  private static final TaxInvoiceBuyer BUYER = TaxInvoiceRules.normalizeBuyer(
      new TaxInvoiceBuyer(" บริษัท ลูกค้า จำกัด ", null, "0-1055-50123-45-1", "head", "00009", " กรุงเทพฯ "));

  private static TaxInvoiceBuyer with(TaxInvoiceBuyer b, String field, String value) {
    return new TaxInvoiceBuyer(field.equals("name") ? value : b.name(), b.idType(), field.equals("taxId") ? value : b.taxId(),
        field.equals("branchType") ? value : b.branchType(), field.equals("branchNo") ? value : b.branchNo(),
        field.equals("address") ? value : b.address());
  }

  @Test
  void normalizesBuyerTextTaxIdAndTheBranchOfAHeadOffice() {
    assertThat(BUYER).isEqualTo(new TaxInvoiceBuyer("บริษัท ลูกค้า จำกัด", "tax_id", "0105550123451", "head", "", "กรุงเทพฯ"));
  }

  @Test
  void identifiesAForeignBuyerByPassportWithoutABranch() {
    TaxInvoiceBuyer tourist = TaxInvoiceRules.normalizeBuyer(
        new TaxInvoiceBuyer("John Smith", "passport", " ab-1234567 ", "branch", "00001", "Hotel ABC, Bangkok"));
    assertThat(tourist.taxId()).isEqualTo("AB1234567");
    assertThat(tourist.branchType()).isEqualTo("none");
    assertThat(tourist.branchNo()).isEmpty();
    assertThat(TaxInvoiceRules.buyerError(tourist)).isNull();
    assertThat(TaxInvoiceRules.buyerError(with(tourist, "taxId", "AB12"))).isEqualTo("เลขที่หนังสือเดินทางต้องเป็นตัวอักษรอังกฤษ/ตัวเลข 6–20 ตัว");
    assertThat(TaxInvoiceRules.buyerError(with(tourist, "taxId", "AB/123456"))).isNotNull();
  }

  @Test
  void keepsNoBranchForBuyersWhoAreNotVatRegistrants() {
    TaxInvoiceBuyer person = TaxInvoiceRules.normalizeBuyer(with(with(BUYER, "branchType", "none"), "branchNo", "00001"));
    assertThat(person.branchType()).isEqualTo("none");
    assertThat(person.branchNo()).isEmpty();
    assertThat(TaxInvoiceRules.buyerError(person)).isNull();
  }

  @Test
  void validatesTheBuyer() {
    assertThat(TaxInvoiceRules.buyerError(BUYER)).isNull();
    assertThat(TaxInvoiceRules.buyerError(TaxInvoiceRules.normalizeBuyer(new TaxInvoiceBuyer("", "tax_id", "", "none", "", ""))))
        .isEqualTo("กรุณากรอกชื่อผู้ซื้อ");
    assertThat(TaxInvoiceRules.buyerError(with(BUYER, "taxId", "0105550123452"))).isEqualTo("เลขประจำตัวผู้เสียภาษีผู้ซื้อต้องเป็น 13 หลักที่ถูกต้อง");
    assertThat(TaxInvoiceRules.buyerError(with(with(BUYER, "branchType", "branch"), "branchNo", "1")))
        .isEqualTo("เลขที่สาขาผู้ซื้อต้องเป็นตัวเลข 5 หลัก");
    assertThat(TaxInvoiceRules.buyerError(with(BUYER, "address", ""))).isEqualTo("กรุณากรอกที่อยู่ผู้ซื้อ");
  }

  private static TaxInvoice invoice(long id, Instant cancelledAt) {
    return new TaxInvoice(id, "INV-" + id, 1, "", null, null, null, BUYER, "", cancelledAt, "", null, null, false, 1L);
  }

  @Test
  void cancelsAndReissuesOnlyAValidInvoiceOfAPaidBillWithAReason() {
    TaxInvoice valid = invoice(1, null);
    assertThat(TaxInvoiceRules.reissueError("paid", valid, BUYER, "ชื่อผิด")).isNull();
    assertThat(TaxInvoiceRules.reissueError("paid", valid, BUYER, " ")).isEqualTo("กรุณาระบุเหตุผลที่ยกเลิกใบเดิม");
    assertThat(TaxInvoiceRules.reissueError("paid", null, BUYER, "x")).isEqualTo("บิลนี้ไม่มีใบกำกับภาษีเต็มรูปที่ใช้งานอยู่");
    assertThat(TaxInvoiceRules.reissueError("paid", invoice(1, Instant.EPOCH), BUYER, "x")).isEqualTo("บิลนี้ไม่มีใบกำกับภาษีเต็มรูปที่ใช้งานอยู่");
    assertThat(TaxInvoiceRules.reissueError("cancelled", valid, BUYER, "x")).isEqualTo("ออกใบใหม่ได้เฉพาะบิลที่ชำระแล้ว");
    assertThat(TaxInvoiceRules.reissueError("paid", valid, with(BUYER, "name", ""), "x")).isEqualTo("กรุณากรอกชื่อผู้ซื้อ");
  }

  @Test
  void picksTheValidInvoiceOfABillElseTheLatest() {
    TaxInvoice a = invoice(1, Instant.EPOCH);
    TaxInvoice b = invoice(2, null);
    assertThat(TaxInvoiceRules.currentInvoice(List.of(a, b))).isEqualTo(b);
    assertThat(TaxInvoiceRules.currentInvoice(List.of(a))).isEqualTo(a);
    assertThat(TaxInvoiceRules.currentInvoice(List.of())).isNull();
  }

  @Test
  void issuesOncePerPaidPosBillOfAVatRegisteredStore() {
    assertThat(TaxInvoiceRules.taxInvoiceError("paid", true, null, true, BUYER, true)).isNull();
    assertThat(TaxInvoiceRules.taxInvoiceError("paid", true, null, true, BUYER, false)).isEqualTo("ร้านไม่ได้จดทะเบียน VAT ออกใบกำกับภาษีไม่ได้");
    assertThat(TaxInvoiceRules.taxInvoiceError("cancelled", true, null, true, BUYER, true))
        .isEqualTo("ออกใบกำกับภาษีได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว");
    assertThat(TaxInvoiceRules.taxInvoiceError("paid", false, null, true, BUYER, true)).isEqualTo("ออกใบกำกับภาษีได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว");
    assertThat(TaxInvoiceRules.taxInvoiceError("paid", true, "INV-1", true, BUYER, true)).isEqualTo("บิลนี้ออกใบกำกับภาษีเต็มรูปแล้ว (INV-1)");
    assertThat(TaxInvoiceRules.taxInvoiceError("paid", true, null, false, BUYER, true))
        .isEqualTo("บิลนี้ขายตอนร้านยังไม่ได้จด VAT ออกใบกำกับภาษีไม่ได้");
    assertThat(TaxInvoiceRules.checkoutBuyerError(BUYER, false)).isEqualTo("ร้านไม่ได้จดทะเบียน VAT ออกใบกำกับภาษีไม่ได้");
  }

  @Test
  void refusesAFullCardNumberInAReference() {
    assertThat(PosPayment.isFullCardNumber("4111 1111 1111 1111")).isTrue();
    assertThat(PosPayment.isFullCardNumber("4111-1111-1111-1111")).isTrue();
    assertThat(PosPayment.isFullCardNumber("4111111111111112")).isFalse();
    assertThat(PosPayment.isFullCardNumber("1111")).isFalse();
    assertThat(PosPayment.isFullCardNumber(null)).isFalse();
  }
}
