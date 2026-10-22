package com.an22.api.model;

import java.util.List;

/**
 * {@code UserPayload} / {@code NewUserPayload} ขาเข้า (user.model.ts) — field ที่ไม่ส่ง = null (PUT คงค่าเดิม);
 * {@code password} ใช้เฉพาะ POST.
 */
public record UserPayload(
    String username,
    String name,
    String email,
    String role,
    Boolean active,
    List<Long> branchIds,
    Boolean allBranches,
    String password) {}
