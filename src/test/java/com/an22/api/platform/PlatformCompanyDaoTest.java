package com.an22.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ลำดับลบของ purge: ลูกก่อนแม่ตาม FK. */
class PlatformCompanyDaoTest {

  @Test
  void deletesChildrenBeforeTheirParents() {
    Map<String, Set<String>> parents = Map.of(
        "dt_user_branches", Set.of("dt_users", "dt_branches"),
        "dt_users", Set.of("dt_companies"),
        "dt_held_bills", Set.of("dt_pos_terminals", "dt_branches"),
        "dt_pos_terminals", Set.of("dt_branches"));
    List<String> order = PlatformCompanyDao.childrenFirst(
        List.of("dt_branches", "dt_held_bills", "dt_pos_terminals", "dt_user_branches", "dt_users"), parents);
    assertThat(order.indexOf("dt_user_branches")).isLessThan(order.indexOf("dt_users")).isLessThan(order.indexOf("dt_branches"));
    assertThat(order.indexOf("dt_held_bills")).isLessThan(order.indexOf("dt_pos_terminals"));
    assertThat(order.indexOf("dt_pos_terminals")).isLessThan(order.indexOf("dt_branches"));
    assertThatThrownBy(() -> PlatformCompanyDao.childrenFirst(List.of("dt_a", "dt_b"), Map.of("dt_a", Set.of("dt_b"), "dt_b", Set.of("dt_a"))))
        .isInstanceOf(IllegalStateException.class);
  }
}
