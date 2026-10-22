package com.an22.api.model;

import java.util.List;

/** {@code MeResponse} (auth.model.ts) — session ของผู้ใช้ร้าน: ตอบ {@code GET /me} และอยู่ใน {@code LoginResponse}. */
public record Session(User user, Company company, List<Branch> branches, BillingNotice billing, boolean canSeeCost) {}
