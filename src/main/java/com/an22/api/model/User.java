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
    Instant createdAt,
    /** E55: มี Microsoft Authenticator ที่ยืนยันแล้ว (server-derived, dt_user_mfa) */
    boolean mfaEnabled) {

  /** ผู้ใช้ที่ยังไม่รู้ / ไม่มี MFA (ฟอร์ม, กฎ, test). */
  public User(
      long id,
      String username,
      String name,
      String email,
      String role,
      boolean active,
      List<Long> branchIds,
      boolean allBranches,
      Instant createdAt) {
    this(id, username, name, email, role, active, branchIds, allBranches, createdAt, false);
  }
}
