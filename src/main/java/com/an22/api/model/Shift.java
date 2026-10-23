package com.an22.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * {@code Shift} (shift.model.ts): 1 กะ = 1 เครื่อง + 1 แคชเชียร์ — เลข {@code SH-<สาขา>-YYYYMMDD-NN}; lockedAt = หน้าจอ POS ถูกล็อก (E48);
 * adjustmentTotal = Σ การปรับปรุงหลังปิด (ไม่มี = ไม่ส่ง key เหมือน mock).
 */
public record Shift(
    long id,
    String shiftNo,
    int terminalId,
    long branchId,
    String posId,
    String cashier,
    String status,
    Instant openedAt,
    double openingFloat,
    List<DenominationCount> openingDenominations,
    Instant closedAt,
    String closedBy,
    List<ShiftCount> counts,
    List<DenominationCount> cashDenominations,
    double depositAmount,
    double floatLeft,
    String approvalStatus,
    String approvedBy,
    Instant approvedAt,
    String note,
    Instant lockedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) Double adjustmentTotal) {}
