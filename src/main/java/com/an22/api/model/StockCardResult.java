package com.an22.api.model;

import java.time.LocalDate;
import java.util.List;

/** {@code StockCardResult} (costing.model.ts) — {@code buildStockCard()}. */
public record StockCardResult(
    LocalDate from,
    LocalDate to,
    Balance opening,
    List<StockMovement> openingMovements,
    List<StockMovement> movements,
    Balance closing,
    Totals totals) {

  /** {@code StockBalance}: ยอดคงเหลือ (value ถูกซ่อนพร้อม avgCost สำหรับคนที่เห็นทุนไม่ได้). */
  public record Balance(int qty, double avgCost, double value) {}

  public record Totals(int inQty, double inCost, int outQty, double outCost) {}
}
