package com.an22.api.rules;

/** port จาก {@code core/models/cost-visibility.model.ts} — {@code hideCost()} มาในขั้น 5 (CostHidingAdvice). */
public final class CostVisibility {

  private CostVisibility() {}

  /** owner / admin เห็นทุนเสมอ, role user เมื่อบริษัทเปิด {@code staffSeeCost} (E43); ไม่มีผู้ใช้ = ไม่เห็น. */
  public static boolean canSeeCost(String role, boolean staffSeeCost) {
    if (role == null) {
      return false;
    }
    return "admin".equals(role) || "owner".equals(role) || staffSeeCost;
  }
}
