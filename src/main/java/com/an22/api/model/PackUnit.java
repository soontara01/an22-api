package com.an22.api.model;

/** {@code PackUnit} (product.model.ts): หน่วยใหญ่ เช่น 1 ลัง = 24 ชิ้น พร้อมบาร์โค้ดของมัน. */
public record PackUnit(String unit, Integer factor, String barcode) {}
