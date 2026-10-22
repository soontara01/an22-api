package com.an22.api.model;

import java.math.BigDecimal;

/**
 * {@code CompanyInfo} (company.model.ts, E11) — ตัวเลขเป็น BigDecimal เพื่อให้กฎตรวจ "จำนวนเต็ม" ได้แบบ {@code Number.isInteger()}
 * (ตอบ JSON เป็นตัวเลขไม่มีทศนิยมเกิน).
 */
public record CompanyInfo(
    String name,
    String taxId,
    Boolean vatRegistered,
    String taxInvoicePaper,
    BigDecimal exchangeDays,
    BigDecimal manualDiscountMaxPercent,
    Boolean requireShift,
    BigDecimal shiftVarianceLimit,
    BigDecimal shiftMaxHours,
    Boolean staffSeeCost) {}
