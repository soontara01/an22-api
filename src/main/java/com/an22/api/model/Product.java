package com.an22.api.model;

import java.util.List;

/**
 * {@code Product} / {@code ProductPayload} (product.model.ts) — ขาออก: stock / avgCost / minStock / maxStock ของสาขาที่ดู (D1),
 * categoryPath / currentPrice คำนวณตอนอ่าน. ขาเข้า: field ที่ไม่ส่ง = null (POST → ค่าเริ่ม, PUT → ค่าเดิม); ค่าที่ server คำนวณไม่ใช้.
 */
public record Product(
    Long id,
    String sku,
    String name,
    String shortName,
    Long categoryId,
    String categoryPath,
    String unit,
    String barcode,
    List<PackUnit> packUnits,
    String brand,
    String model,
    String itemType,
    String imageUrl,
    String vatType,
    Double cost,
    Double avgCost,
    Double currentPrice,
    Integer stock,
    Integer minStock,
    Integer maxStock,
    List<SkuSupplier> suppliers,
    Integer warrantyMonths,
    Boolean serialControl,
    String serialPrefix,
    Integer serialLength,
    String saleStatus) {}
