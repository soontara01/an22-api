package com.an22.api.model;

/** {@code BranchStockRow} (branch-stock.model.ts): ทุกคนเห็นจำนวน, avgCost เฉพาะคนที่เห็นทุน (CostHidingAdvice → null). */
public record BranchStockRow(long branchId, int stock, double avgCost) {}
