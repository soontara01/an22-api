package com.an22.api.model;

/**
 * {@code SkuPrice} / {@code SkuPricePayload} (price.model.ts) — วันที่เป็น 'YYYY-MM-DD' ท้องถิ่น ปลายรวมทั้งสองข้าง, endDate null = ไม่กำหนด;
 * ราคาเป็น Double ให้ {@code moneyInputError()} ตรวจแบบ JS ได้ตรง.
 */
public record SkuPrice(Long id, Long productId, Double price, String startDate, String endDate, String note) {}
