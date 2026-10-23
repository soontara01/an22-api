package com.an22.api.model;

import java.util.List;

/** {@code SaleLine} (sale.model.ts): snapshot ของ SKU ตอนขาย — เงินรวม VAT, cogs ไม่รวม VAT (ทุนทั้งบรรทัด, ซ่อนจากคนที่เห็นทุนไม่ได้). */
public record SaleLine(
    long productId,
    String sku,
    String name,
    String shortName,
    String unit,
    int factor,
    int qty,
    double unitPrice,
    double listPrice,
    double itemDiscount,
    double billDiscount,
    double manualDiscount,
    double amount,
    String vatType,
    String itemType,
    double vat,
    List<Long> promotionIds,
    Long freeOfPromotionId,
    String serial,
    int warrantyMonths,
    double cogs) {}
