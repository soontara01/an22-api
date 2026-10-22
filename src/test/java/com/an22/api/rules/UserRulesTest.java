package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.User;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code user.model.spec.ts}, {@code cost-visibility.model.spec.ts}, {@code company.model.ts}. */
class UserRulesTest {

  private static User user(String role, boolean all, List<Long> branchIds) {
    return new User(1, "u", "U", "", role, true, branchIds, all, null);
  }

  private static Branch branch(long id, boolean active) {
    return new Branch(id, "B" + id, "b", "0000" + id, "x", "", "", active);
  }

  @Test
  void knowsTheCompanyAdminAndWhoMayWorkInABranch() {
    assertThat(UserRules.isCompanyAdmin("admin", true)).isTrue();
    assertThat(UserRules.isCompanyAdmin("admin", false)).isFalse();
    assertThat(UserRules.isCompanyAdmin("user", true)).isFalse();
    assertThat(UserRules.isCompanyAdmin("owner", false)).isTrue();
    assertThat(UserRules.canWorkIn(user("user", false, List.of(1L)), branch(1, true))).isTrue();
    assertThat(UserRules.canWorkIn(user("user", false, List.of(1L)), branch(2, true))).isFalse();
    assertThat(UserRules.canWorkIn(user("admin", true, List.of()), branch(3, false))).isFalse();
  }

  @Test
  void adminRightsAndOwner() {
    assertThat(UserRules.hasAdminRights("owner")).isTrue();
    assertThat(UserRules.hasAdminRights("admin")).isTrue();
    assertThat(UserRules.hasAdminRights("user")).isFalse();
    assertThat(UserRules.hasAdminRights(null)).isFalse();
    assertThat(UserRules.isOwner("owner")).isTrue();
    assertThat(UserRules.isOwner("admin")).isFalse();
  }

  @Test
  void normalizesUsernameAndCompanyCode() {
    assertThat(UserRules.normalizeUsername("  SomChai ")).isEqualTo("somchai");
    assertThat(UserRules.normalizeUsername(null)).isEmpty();
    assertThat(CompanyRules.normalizeCompanyCode(" demo ")).isEqualTo("DEMO");
    assertThat(CompanyRules.normalizeCompanyCode(null)).isEmpty();
  }

  @Test
  void adminsAndOwnerAlwaysSeeCostStaffOnlyWhenAllowed() {
    assertThat(CostVisibility.canSeeCost("owner", false)).isTrue();
    assertThat(CostVisibility.canSeeCost("admin", false)).isTrue();
    assertThat(CostVisibility.canSeeCost("user", false)).isFalse();
    assertThat(CostVisibility.canSeeCost("user", true)).isTrue();
    assertThat(CostVisibility.canSeeCost(null, true)).isFalse();
  }
}
