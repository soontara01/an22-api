package com.an22.api.model;

/**
 * {@code Supplier} / {@code SupplierPayload} (supplier.model.ts) — ขาเข้า field ที่ไม่ส่ง = null → ค่าเริ่ม ({@code SUPPLIER_DEFAULTS}),
 * {@code id} / {@code productCount} ไม่ใช้; {@code productCount} คำนวณตอนอ่าน (ขั้น 5).
 */
public record Supplier(
    Long id,
    String code,
    String name,
    String taxId,
    String branchType,
    String branchNo,
    String address,
    String contactName,
    String phone,
    String email,
    Integer creditDays,
    String bankName,
    String bankAccountNo,
    String bankAccountName,
    String note,
    Boolean active,
    Integer productCount) {}
