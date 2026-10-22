package com.an22.api.model;

/** {@code Category} (category.model.ts) — {@code productCount} คำนวณตอนอ่าน (นับ SKU ได้ตั้งแต่ขั้น 5). */
public record Category(long id, String code, String name, Long parentId, int level, boolean active, int productCount) {}
