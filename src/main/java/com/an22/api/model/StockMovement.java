package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/** {@code StockMovement} (costing.model.ts): 1 บรรทัดของบัตรสต็อก — ยอดคงเหลือหลังรายการของ (สาขา, SKU). */
public record StockMovement(
    long id,
    long productId,
    long branchId,
    Instant date,
    String type,
    int qty,
    double unitCost,
    double totalCost,
    int balanceQty,
    double balanceAvgCost,
    double balanceValue,
    List<String> serials,
    String note) {}
