package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/**
 * {@code CreditNote} (credit-note.model.ts): ใบลดหนี้ {@code CN-<สาขา>-YYYYMMDD-NNNN} — รับของคืนจากบิลที่ชำระแล้วตั้งแต่วันถัดจากวันขาย; บิลไม่เปลี่ยน.
 * buyer = ผู้ซื้อของใบกำกับตอนออก (null = มีแค่ใบกำกับอย่างย่อ).
 */
public record CreditNote(
    long id,
    String cnNo,
    long saleId,
    String orderNo,
    String taxInvoiceNo,
    TaxInvoiceBuyer buyer,
    Instant saleDate,
    Instant date,
    String cashier,
    String reason,
    List<Line> lines,
    List<Deduction> deductions,
    double subtotal,
    double deduction,
    double total,
    double vat,
    List<SalePayment> refunds,
    Long shiftId,
    Long branchId,
    Integer terminalId,
    String posId,
    Boolean vatRegistered) {

  /** {@code CreditNoteLine}: ยอดคืน / VAT / ทุนของหน่วยที่คืน; restock false = ตัดเสีย (บริการ false เสมอ). */
  public record Line(
      int saleLineIndex,
      long productId,
      String sku,
      String name,
      String shortName,
      String unit,
      int factor,
      int qty,
      double amount,
      String vatType,
      double vat,
      double cogs,
      String serial,
      boolean free,
      boolean restock) {}

  /** {@code CreditNoteDeduction}: ของแถมที่ลูกค้าเก็บไว้เกินสิทธิ์ (หน่วยฐาน) หักที่ราคาตอนขาย. */
  public record Deduction(
      long promotionId, long productId, String sku, String name, int qty, double unitPrice, double amount, String vatType) {}

  /** {@code CreditNoteLineInput}: qty เป็น Double ให้กฎจำนวนเต็มทำงาน. */
  public record LineInput(Integer saleLineIndex, Double qty, Boolean restock) {}

  /** {@code CreditNotePayload} ({@code POST /sales/:id/credit-notes}). */
  public record Payload(List<LineInput> lines, List<PaymentInput> refunds, String reason, Double expectedTotal) {}

  /** {@code CreditNoteDraft}: error null = ร่างใช้ได้. */
  public record Draft(List<Line> lines, List<Deduction> deductions, double subtotal, double deduction, double total, double vat,
      String error) {}
}
