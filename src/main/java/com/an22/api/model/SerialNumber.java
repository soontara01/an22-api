package com.an22.api.model;

import java.time.Instant;

/** {@code SerialNumber} (serial.model.ts): 1 เครื่องของ SKU คุม serial — ไม่ลบ, ตัดออก = เปลี่ยน status. */
public record SerialNumber(
    long id,
    long productId,
    long branchId,
    String serial,
    String status,
    Instant receivedAt,
    Instant removedAt,
    String note,
    double cost) {}
