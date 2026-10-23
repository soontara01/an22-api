package com.an22.api.model;

import java.time.Instant;

/** {@code ShiftAdjustment}: ปรับปรุงยอดของกะที่ปิดแล้ว (S8) — + = มากกว่าที่นับ, − = น้อยกว่า. */
public record ShiftAdjustment(
    long id, long shiftId, long methodId, String name, double amount, String reasonCode, String reason, String by, Instant at) {}
