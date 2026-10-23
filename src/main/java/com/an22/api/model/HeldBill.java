package com.an22.api.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * {@code HeldBill} (held-bill.model.ts, E48): บิลที่ POS พักไว้ ต่อสาขา + เครื่อง — id ออกโดย server; buyer = {@code TaxInvoiceBuyer}
 * (เก็บตามที่ส่ง — ตอนเรียกคืนคิดราคาใหม่); total ไว้แสดงเท่านั้น.
 */
public record HeldBill(
    String id,
    String heldAt,
    String customer,
    List<CartItem> items,
    List<FreeSerial> freeSerials,
    JsonNode buyer,
    ManualDiscount billManual,
    String manualReason,
    Double total) {}
