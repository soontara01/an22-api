package com.an22.api.rules;

import java.util.Set;

/** port จาก {@code core/models/cost-visibility.model.ts} — {@code hideCost()} = {@code web/CostHidingAdvice}. */
public final class CostVisibility {

  /** {@code COST_FIELDS}: key ที่เป็นทุน / มูลค่าที่ทุน ในทุก response. */
  public static final Set<String> COST_FIELDS =
      Set.of("cost", "avgCost", "cogs", "unitCost", "totalCost", "balanceAvgCost", "balanceValue", "costIn", "costOut", "inCost",
          "outCost");

  private CostVisibility() {}

  /** owner / admin เห็นทุนเสมอ, role user เมื่อบริษัทเปิด {@code staffSeeCost} (E43); ไม่มีผู้ใช้ = ไม่เห็น. */
  public static boolean canSeeCost(String role, boolean staffSeeCost) {
    if (role == null) {
      return false;
    }
    return "admin".equals(role) || "owner".equals(role) || staffSeeCost;
  }
}
