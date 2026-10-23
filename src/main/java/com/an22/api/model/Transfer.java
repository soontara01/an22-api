package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/** {@code Transfer} (transfer.model.ts) — ใบโอนระหว่างสาขา, เลข {@code TR-<สาขา>-YYYYMMDD-NNNN} (E21 / E26–E29). */
public record Transfer(
    long id,
    String transferNo,
    long fromBranchId,
    long toBranchId,
    String status,
    Instant createdAt,
    String createdBy,
    String note,
    List<TransferLine> lines,
    Instant receivedAt,
    String receivedBy,
    String shortReason,
    Instant cancelledAt,
    String cancelledBy,
    String cancelReason) {}
