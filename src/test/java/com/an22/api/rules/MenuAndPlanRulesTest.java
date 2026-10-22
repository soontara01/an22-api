package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.MenuAccess;
import com.an22.api.rules.PlanRules.LimitKind;
import com.an22.api.rules.PlanRules.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code menu-access.model.spec.ts} + {@code plan.model.spec.ts}. */
class MenuAndPlanRulesTest {

  private static final List<String> KNOWN = List.of("dashboard", "pos", "sales", "inventory", "users", "settings");

  private static MenuAccess menus(String... m) {
    return new MenuAccess(List.of(m));
  }

  @Test
  void acceptsKnownMenusForTheUserRoleNeverAdminOnes() {
    assertThat(MenuAccessRules.menuAccessError(menus("pos", "sales"), KNOWN)).isNull();
    assertThat(MenuAccessRules.menuAccessError(menus(), KNOWN)).isEqualTo("ต้องเลือกอย่างน้อย 1 เมนูให้ผู้ใช้ทั่วไป");
    assertThat(MenuAccessRules.menuAccessError(new MenuAccess(null), KNOWN)).isEqualTo("ต้องเลือกอย่างน้อย 1 เมนูให้ผู้ใช้ทั่วไป");
    assertThat(MenuAccessRules.menuAccessError(menus("pos", "users"), KNOWN)).isEqualTo("เมนูผู้ใช้งาน / บัญชีบริษัท เป็นของผู้ดูแลระบบเท่านั้น");
    assertThat(MenuAccessRules.menuAccessError(menus("reports"), KNOWN)).isEqualTo("ไม่มีเมนู reports");
    assertThat(MenuAccessRules.menuAccessError(menus("pos", "pos"), KNOWN)).isEqualTo("เลือกเมนูซ้ำ");
  }

  @Test
  void showsAdminsEveryMenuAndTheUserRoleItsOwn() {
    MenuAccess access = menus("pos");
    assertThat(MenuAccessRules.canSeeMenu("inventory", true, access)).isTrue();
    assertThat(MenuAccessRules.canSeeMenu("pos", false, access)).isTrue();
    assertThat(MenuAccessRules.canSeeMenu("inventory", false, access)).isFalse();
    assertThat(MenuAccessRules.canSeeMenu("users", false, menus("users"))).isFalse();
    assertThat(MenuAccessRules.DEFAULT_USER_MENUS).containsExactly("dashboard", "pos", "sales", "settings");
    assertThat(MenuAccessRules.canSeeMenu("sales", false, null)).isTrue();
    assertThat(MenuAccessRules.canSeeMenu("sku", false, null)).isFalse();
    assertThat(MenuAccessRules.MENU.keySet()).containsExactly(
        "dashboard", "pos", "sales", "inventory", "transfers", "sku", "pricing", "promotions", "master-data", "users", "account",
        "settings");
  }

  @Test
  void refusesOneMorePastTheLimit() {
    PlanRules.Plan starter = PlanRules.planOf("starter");
    assertThat(PlanRules.planLimitError(LimitKind.BRANCHES, starter, new Usage(0, 0, 0))).isNull();
    assertThat(PlanRules.planLimitError(LimitKind.BRANCHES, starter, new Usage(1, 0, 0)))
        .isEqualTo("แพ็กเกจ Starter ใช้สาขาได้ 1 สาขา — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท");
    assertThat(PlanRules.planLimitError(LimitKind.USERS, starter, new Usage(1, 4, 0))).isNull();
    assertThat(PlanRules.planLimitError(LimitKind.USERS, starter, new Usage(1, 5, 0)))
        .isEqualTo("แพ็กเกจ Starter ใช้ผู้ใช้ที่ใช้งานได้ 5 คน — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท");
    assertThat(PlanRules.planLimitError(LimitKind.TERMINALS, PlanRules.planOf("pro"), new Usage(99, 999, 99))).isNull();
  }

  @Test
  void flagsWhatADowngradeLeftOverAndUnknownIdsAreTheSmallest() {
    assertThat(PlanRules.overLimits(PlanRules.planOf("starter"), new Usage(2, 5, 3))).containsExactly(LimitKind.BRANCHES, LimitKind.TERMINALS);
    assertThat(PlanRules.overLimits(PlanRules.planOf("pro"), new Usage(9, 9, 9))).isEmpty();
    assertThat(PlanRules.planOf("gold")).isSameAs(PlanRules.PLANS.getFirst());
  }
}
