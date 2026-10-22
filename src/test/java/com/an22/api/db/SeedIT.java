package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TenantSql.Actor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** seed DEMO (db/seed) ตรงกับ seedDb() ของ mock. */
class SeedIT extends ApiTest {

  private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
  private static final Actor DEMO = Actor.system(1);

  @Autowired TenantSql sql;

  @Test
  void demoCompanyWithBranchesAndInfo() {
    Map<String, Object> company =
        sql.platform(jdbc -> jdbc.sql("SELECT code, active, plan_id, next_invoice_on::text AS next FROM dt_companies WHERE id = 1").query().singleRow());
    assertThat(company).containsEntry("code", "DEMO").containsEntry("active", true).containsEntry("plan_id", "pro")
        .containsEntry("next", "9999-12-31");

    List<String> branches =
        sql.as(DEMO, jdbc -> jdbc.sql("SELECT code || ':' || branch_no FROM dt_branches ORDER BY id").query(String.class).list());
    assertThat(branches).containsExactly("HQ:00000", "BN:00001");

    Map<String, Object> info = sql.as(DEMO, jdbc -> jdbc.sql("SELECT name, tax_id, staff_see_cost FROM dt_company_info").query().singleRow());
    assertThat(info).containsEntry("name", "บริษัท ไอทีดี คอมพิวเตอร์ จำกัด").containsEntry("tax_id", "0105550123451")
        .containsEntry("staff_see_cost", false);

    List<String> menus =
        sql.as(DEMO, jdbc -> jdbc.sql("SELECT unnest(user_menus) FROM dt_menu_access").query(String.class).list());
    assertThat(menus).containsExactly("dashboard", "pos", "sales", "settings");
  }

  @Test
  void usersAndPasswordsMatchTheMock() {
    List<Map<String, Object>> users =
        sql.as(DEMO, jdbc -> jdbc.sql(
                "SELECT u.username, u.role, u.active, u.all_branches, u.password_hash,"
                    + " array_to_string(ARRAY(SELECT branch_id FROM dt_user_branches b WHERE b.user_id = u.id ORDER BY 1), ',') AS branches"
                    + " FROM dt_users u ORDER BY u.id")
            .query()
            .listOfRows());
    assertThat(users).extracting(u -> u.get("username")).containsExactly("admin", "somchai", "somying", "john");
    assertThat(users.get(0)).containsEntry("role", "owner").containsEntry("all_branches", true).containsEntry("branches", "");
    assertThat(users.get(1)).containsEntry("role", "user").containsEntry("branches", "1");
    assertThat(users.get(2)).containsEntry("active", false);
    assertThat(BCRYPT.matches("admin", (String) users.get(0).get("password_hash"))).isTrue();
    assertThat(BCRYPT.matches("1234", (String) users.get(1).get("password_hash"))).isTrue();

    String platformHash =
        sql.platform(jdbc -> jdbc.sql("SELECT password_hash FROM dt_platform_admins WHERE username = 'platform'").query(String.class).single());
    assertThat(BCRYPT.matches("platform", platformHash)).isTrue();
  }

  @Test
  void newRowsGetIdsAfterTheSeed() {
    sql.rolledBack(Actor.user(1, 1), jdbc -> {
      long id =
          jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, role, password_hash) VALUES (1, 'newbie', 'x', 'user', 'x') RETURNING id")
              .query(Long.class)
              .single();
      assertThat(id).isGreaterThan(4);
    });
  }
}
