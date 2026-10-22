package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TenantSql.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** trigger set_row_stamp(): created_* / updated_* มาจาก app.user_id / app.actor_kind เสมอ. */
class RowStampIT extends ApiTest {

  @Autowired TenantSql sql;

  private static final String STAMPS =
      "SELECT created_at, created_by, created_by_kind, updated_at, updated_by, updated_by_kind FROM dt_branches WHERE code = 'ST'";

  @Test
  void insertAndUpdateRecordWhoAndWhen() {
    sql.rolledBack(Actor.user(1, 2), jdbc -> {
      jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address) VALUES (1, 'ST', 'stamp', '00042', 'x')").update();
      Map<String, Object> inserted = jdbc.sql(STAMPS).query().singleRow();
      assertThat(inserted)
          .containsEntry("created_by", 2L).containsEntry("created_by_kind", "user")
          .containsEntry("updated_by", 2L).containsEntry("updated_by_kind", "user");
      assertThat(inserted.get("updated_at")).isEqualTo(inserted.get("created_at"));

      // ผู้ใช้อื่นแก้ใน transaction เดียวกัน (สลับ actor) — ทับ created_* ไม่ได้
      jdbc.sql("SELECT set_config('app.user_id', '4', true)").query().singleRow();
      jdbc.sql("UPDATE dt_branches SET name = 'stamp 2', created_by = 99, created_by_kind = 'platform',"
              + " created_at = '2000-01-01T00:00:00Z' WHERE code = 'ST'")
          .update();
      Map<String, Object> updated = jdbc.sql(STAMPS).query().singleRow();
      assertThat(updated)
          .containsEntry("created_by", 2L).containsEntry("created_by_kind", "user")
          .containsEntry("created_at", inserted.get("created_at"))
          .containsEntry("updated_by", 4L).containsEntry("updated_by_kind", "user");
    });
  }

  @Test
  void userCannotBackdateButSystemCan() {
    sql.rolledBack(Actor.user(1, 1), jdbc -> {
      jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address, created_at, created_by)"
              + " VALUES (1, 'ST', 'stamp', '00042', 'x', '2000-01-01T00:00:00Z', 99)")
          .update();
      Map<String, Object> row = jdbc.sql(STAMPS).query().singleRow();
      assertThat(((Timestamp) row.get("created_at")).toInstant()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
      assertThat(row).containsEntry("created_by", 1L);
    });
    sql.rolledBack(Actor.system(1), jdbc -> {
      jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address, created_at)"
              + " VALUES (1, 'ST', 'stamp', '00042', 'x', '2000-01-01T00:00:00Z')")
          .update();
      Map<String, Object> row = jdbc.sql(STAMPS).query().singleRow();
      assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
      assertThat(row).containsEntry("created_by", null).containsEntry("created_by_kind", "system");
    });
  }

  @Test
  void appendOnlyTableGetsCreatedStampOnly() {
    sql.rolledBack(Actor.user(1, 1), jdbc -> {
      jdbc.sql("INSERT INTO dt_audit_log (tenant_id, actor_kind, user_id, user_name, action, detail)"
              + " VALUES (1, 'user', 1, 'admin', 'menu_access', 'stamp-test')")
          .update();
      Map<String, Object> row =
          jdbc.sql("SELECT created_by, created_by_kind, created_at FROM dt_audit_log WHERE detail = 'stamp-test'").query().singleRow();
      assertThat(row).containsEntry("created_by", 1L).containsEntry("created_by_kind", "user");
      assertThat(row.get("created_at")).isNotNull();
    });
  }
}
