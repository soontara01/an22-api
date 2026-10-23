package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/**
 * {@code Exchange} (exchange.model.ts): ใบเปลี่ยนสินค้า {@code EX-<สาขา>-YYYYMMDD-NNNN} — SKU / หน่วยเดิม ราคาเดิม ไม่มีเงินเดิน, เดินแค่สต็อก.
 */
public record Exchange(
    long id,
    String exNo,
    long saleId,
    String orderNo,
    Instant saleDate,
    Instant date,
    String cashier,
    String reason,
    List<Line> lines,
    Long shiftId,
    Long branchId,
    Integer terminalId,
    String posId) {

  /** {@code ExchangeLine}: costIn = ทุนของที่คืน, costOut = ทุนของที่ให้ไป. */
  public record Line(
      int saleLineIndex,
      long productId,
      String sku,
      String name,
      String shortName,
      String unit,
      int factor,
      int qty,
      String oldSerial,
      String newSerial,
      boolean restock,
      double costIn,
      double costOut) {}

  /** {@code ExchangeLineInput}. */
  public record LineInput(Integer saleLineIndex, Double qty, String newSerial, Boolean restock) {}

  /** {@code ExchangePayload}. */
  public record Payload(List<LineInput> lines, String reason) {}
}
