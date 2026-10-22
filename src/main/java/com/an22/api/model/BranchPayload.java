package com.an22.api.model;

/** {@code BranchPayload} / {@code BranchDetails} ขาเข้า — field ที่ไม่ส่ง = null (PUT คงค่าเดิม). */
public record BranchPayload(
    String code, String name, String branchNo, String address, String phone, String receiptFooter, Boolean active) {}
