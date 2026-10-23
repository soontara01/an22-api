package com.an22.api.model;

import java.util.List;

/**
 * {@code SalePayload} ({@code POST /sales}, {@code /sales/cost-check}): server คิดตะกร้าใหม่ — expectedTotal ต่างจากที่คิดได้ → 400; buyer = ขอใบกำกับ
 * เต็มรูปพร้อมบิล.
 */
public record SalePayload(
    List<CartItem> items,
    List<FreeSerial> freeSerials,
    List<PaymentInput> payments,
    String customer,
    Double expectedTotal,
    TaxInvoiceBuyer buyer,
    ManualDiscount billManualDiscount,
    String manualDiscountReason) {

  /** {@code VoidPayload}: ไม่ใช่ admin ต้องมี login ของ admin (P3). */
  public record VoidRequest(String reason, ShiftPayloads.Credentials approval) {}

  /** {@code { status }} ของ PUT /sales/:id/status (ออร์เดอร์ก่อน POS). */
  public record Status(String status) {}
}
