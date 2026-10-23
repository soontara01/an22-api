package com.an22.api.rules;

import com.an22.api.model.TaxInvoice;
import com.an22.api.model.TaxInvoiceBuyer;
import java.util.List;
import java.util.Locale;

/** port จาก {@code core/models/tax-invoice.model.ts}: ผู้ซื้อ, ออกใบกำกับเต็มรูป, ยกเลิก + ออกใหม่. */
public final class TaxInvoiceRules {

  public static final String NOT_VAT = "ร้านไม่ได้จดทะเบียน VAT ออกใบกำกับภาษีไม่ได้";

  private TaxInvoiceRules() {}

  /** {@code normalizeBuyerId()}: ตัดช่องว่าง / '-'; passport เป็นตัวใหญ่. */
  public static String normalizeBuyerId(String idType, String id) {
    String clean = id == null ? "" : id.replaceAll("[\\s-]", "");
    return "passport".equals(idType) ? clean.toUpperCase(Locale.ROOT) : clean;
  }

  /** {@code buyerIdError()}. */
  public static String buyerIdError(String idType, String id) {
    if ("passport".equals(idType)) {
      return id != null && id.matches("[A-Z0-9]{6,20}") ? null : "เลขที่หนังสือเดินทางต้องเป็นตัวอักษรอังกฤษ/ตัวเลข 6–20 ตัว";
    }
    return Suppliers.isValidThaiTaxId(id) ? null : "เลขประจำตัวผู้เสียภาษีผู้ซื้อต้องเป็น 13 หลักที่ถูกต้อง";
  }

  /** {@code normalizeBuyer()}: passport ไม่มีสาขา; ไม่ระบุสาขา (ข้อมูลเก่า) = สำนักงานใหญ่. */
  public static TaxInvoiceBuyer normalizeBuyer(TaxInvoiceBuyer b) {
    String idType = "passport".equals(b.idType()) ? "passport" : "tax_id";
    String branchType = "passport".equals(idType) ? "none"
        : "branch".equals(b.branchType()) || "none".equals(b.branchType()) ? b.branchType() : "head";
    return new TaxInvoiceBuyer(strip(b.name()), idType, normalizeBuyerId(idType, b.taxId()), branchType,
        "branch".equals(branchType) ? strip(b.branchNo()) : "", strip(b.address()));
  }

  /** {@code buyerError()} (ผู้ซื้อที่ normalize แล้ว). */
  public static String buyerError(TaxInvoiceBuyer b) {
    if (b.name().isEmpty()) {
      return "กรุณากรอกชื่อผู้ซื้อ";
    }
    String idProblem = buyerIdError(b.idType(), b.taxId());
    if (idProblem != null) {
      return idProblem;
    }
    if ("branch".equals(b.branchType()) && !b.branchNo().matches("\\d{5}")) {
      return "เลขที่สาขาผู้ซื้อต้องเป็นตัวเลข 5 หลัก";
    }
    return b.address().isEmpty() ? "กรุณากรอกที่อยู่ผู้ซื้อ" : null;
  }

  /** {@code currentInvoice()}: ใบที่ยังใช้ได้ ไม่งั้นใบล่าสุด (เรียงเก่าก่อน). */
  public static TaxInvoice currentInvoice(List<TaxInvoice> invoices) {
    return invoices.stream().filter(i -> i.cancelledAt() == null).findFirst()
        .orElse(invoices.isEmpty() ? null : invoices.getLast());
  }

  /** {@code reissueError()}: ยกเลิก + ออกใหม่เฉพาะใบที่ใช้อยู่ของบิลที่ชำระแล้ว พร้อมเหตุผล. */
  public static String reissueError(String saleStatus, TaxInvoice current, TaxInvoiceBuyer buyer, String reason) {
    if (!"paid".equals(saleStatus)) {
      return "ออกใบใหม่ได้เฉพาะบิลที่ชำระแล้ว";
    }
    if (current == null || current.cancelledAt() != null) {
      return "บิลนี้ไม่มีใบกำกับภาษีเต็มรูปที่ใช้งานอยู่";
    }
    if (reason == null || reason.isBlank()) {
      return "กรุณาระบุเหตุผลที่ยกเลิกใบเดิม";
    }
    return buyerError(buyer);
  }

  /** {@code checkoutBuyerError()}: ผู้ซื้อที่กรอกที่ POS (ออกใบพร้อมบิล). */
  public static String checkoutBuyerError(TaxInvoiceBuyer buyer, boolean storeVatRegistered) {
    return storeVatRegistered ? buyerError(buyer) : NOT_VAT;
  }

  /** {@code taxInvoiceError()}: ออกทีหลังจากบิล — ร้าน / บิลจด VAT, บิล POS ที่ชำระแล้ว, ยังไม่เคยออก. */
  public static String taxInvoiceError(String saleStatus, boolean hasLines, String taxInvoiceNo, boolean soldWithVat, TaxInvoiceBuyer buyer,
      boolean storeVatRegistered) {
    if (!storeVatRegistered) {
      return NOT_VAT;
    }
    if (!soldWithVat) {
      return "บิลนี้ขายตอนร้านยังไม่ได้จด VAT ออกใบกำกับภาษีไม่ได้";
    }
    if (!"paid".equals(saleStatus) || !hasLines) {
      return "ออกใบกำกับภาษีได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว";
    }
    if (taxInvoiceNo != null) {
      return "บิลนี้ออกใบกำกับภาษีเต็มรูปแล้ว (" + taxInvoiceNo + ")";
    }
    return buyerError(buyer);
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
