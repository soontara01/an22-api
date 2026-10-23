package com.an22.api.model;

import java.time.Instant;

/** {@code CashMovement}: เงินเข้า / ออกลิ้นชักระหว่างกะ (ไม่ใช่การขาย). */
public record CashMovement(long id, long shiftId, String type, double amount, String reason, String by, Instant at) {}
