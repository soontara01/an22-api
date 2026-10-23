package com.an22.api.model;

import java.time.Instant;

/** {@code TaxInvoice}: ใบกำกับภาษีเต็มรูป {@code INV-<สาขาของบิล>-<วันขาย>-NNNN} — ลงวันที่ตามวันขาย, ยกเลิก + ออกใหม่แทนการแก้. */
public record TaxInvoice(
    long id,
    String invoiceNo,
    long saleId,
    String orderNo,
    Instant saleDate,
    Instant date,
    Instant issuedAt,
    TaxInvoiceBuyer buyer,
    String issuedBy,
    Instant cancelledAt,
    String cancelReason,
    String replacesInvoiceNo,
    String replacedByNo,
    boolean atSale,
    Long branchId) {

  /** {@code { buyer }} / {@code { buyer, reason }} ขาเข้า. */
  public record Request(TaxInvoiceBuyer buyer, String reason) {}
}
