package com.an22.api.model;

/** {@code SignupPayload} (signup.model.ts, E37) ขาเข้า — field ที่ไม่ส่ง = null (ถือเป็นค่าว่างแบบ mock). */
public record SignupPayload(
    String companyCode, String companyName, String taxId, Boolean vatRegistered, BranchPayload branch, Owner owner, String planId) {

  public record Owner(String name, String username, String email, String password) {}
}
