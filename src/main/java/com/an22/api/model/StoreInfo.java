package com.an22.api.model;

import java.math.BigDecimal;

/** {@code StoreInfo} (store-info.model.ts) — ประกอบจาก CompanyInfo + สาขา ({@code storeInfoOf()}), ไม่เก็บ (E11). */
public record StoreInfo(
    String name,
    String placeName,
    Boolean vatRegistered,
    String taxId,
    String branchType,
    String branchNo,
    String address,
    String phone,
    String receiptFooter,
    String taxInvoicePaper,
    BigDecimal exchangeDays,
    BigDecimal manualDiscountMaxPercent,
    Boolean requireShift,
    BigDecimal shiftVarianceLimit,
    BigDecimal shiftMaxHours,
    Boolean staffSeeCost) {}
