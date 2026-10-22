package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.User;
import java.util.Locale;

/** port จาก {@code core/models/user.model.ts} (ส่วนที่ขั้น 2 ใช้ — ที่เหลือมาในขั้น 3). */
public final class UserRules {

  private UserRules() {}

  /** สิทธิ์ admin ทุกอย่าง: admin หรือ owner (E33). */
  public static boolean hasAdminRights(String role) {
    return "admin".equals(role) || "owner".equals(role);
  }

  public static boolean isOwner(String role) {
    return "owner".equals(role);
  }

  /** งานระดับบริษัท (E5 / E6): owner หรือ admin ทุกสาขา. */
  public static boolean isCompanyAdmin(String role, boolean allBranches) {
    return isOwner(role) || ("admin".equals(role) && allBranches);
  }

  /** ทำงานในสาขานี้ได้ไหม — สาขาที่ปิดใช้งานไม่เปิดให้ใคร. */
  public static boolean canWorkIn(User user, Branch branch) {
    return branch.active() && (user.allBranches() || user.branchIds().contains(branch.id()));
  }

  /** {@code normalizeUsername()}: ตัดช่องว่างหัวท้าย + ตัวเล็ก. */
  public static String normalizeUsername(String username) {
    return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
  }
}
