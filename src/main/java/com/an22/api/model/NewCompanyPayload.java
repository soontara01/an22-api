package com.an22.api.model;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code NewCompanyPayload} (signup.model.ts, E59) ขาเข้าของ {@code POST /platform/companies} — field ที่ไม่ส่ง = null (ถือเป็นค่าว่างแบบ mock);
 * owner ไม่มีรหัสผ่าน (ตั้งเองด้วยลิงก์), {@code trialDays} ไม่ส่ง = {@code TRIAL_DAYS}.
 */
public record NewCompanyPayload(
    String companyCode, String companyName, String taxId, Boolean vatRegistered, BranchPayload branch, Owner owner, String planId,
    @JsonDeserialize(using = IntegerOrInvalid.class) Integer trialDays) {

  public record Owner(String name, String username, String email) {}
}
