package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/** {@code User} (user.model.ts) — ไม่มีรหัสผ่าน; {@code branchIds} ว่างเมื่อ {@code allBranches}. */
public record User(
    long id,
    String username,
    String name,
    String email,
    String role,
    boolean active,
    List<Long> branchIds,
    boolean allBranches,
    Instant createdAt) {}
