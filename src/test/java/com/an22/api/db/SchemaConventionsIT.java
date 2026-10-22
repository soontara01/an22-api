package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * กติกา schema (docs/plan-step.md หัวข้อ "Schema") ตรวจกับ DB จริงหลัง migrate — ครอบทุก migration ในอนาคตเอง:
 * ตารางใหม่ที่ลืม RLS / trigger / index ของ FK / grant ผิด → test นี้แดง.
 */
class SchemaConventionsIT extends ApiTest {

  /** ตารางที่ an22_app แตะได้ทั้งที่ไม่มี tenant_id (+ สิทธิ์ที่ได้) — นอกนี้ห้ามมีสิทธิ์. */
  private static final List<String> APP_PLATFORM_GRANTS =
      List.of(
          "dt_companies:SELECT",
          "dt_billing_invoices:SELECT",
          "flyway_schema_history:SELECT" /* validate ของ FLYWAY_MIGRATE=false */);

  /** ตาราง platform ที่ an22_app อ่านได้ต้องอยู่ใต้ RLS (เห็นเฉพาะบริษัทตัวเอง). */
  private static final List<String> APP_READABLE_PLATFORM_TABLES = List.of("dt_companies", "dt_billing_invoices");

  /** ตาราง append-only: an22_app ห้าม UPDATE / DELETE, ไม่มี updated_*. */
  private static final List<String> APPEND_ONLY = List.of("dt_audit_log");

  private static List<String> rows(String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = TestDatabase.superuser(); ResultSet rs = c.createStatement().executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static final String TABLES =
      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'";

  private static final String TENANT_TABLES =
      TABLES + " AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)";

  @Test
  void everyTableIsPrefixedAndOwnedByOwner() throws Exception {
    assertThat(rows(TABLES)).isNotEmpty().allMatch(name -> name.matches("dt_[a-z0-9_]+"));
    assertThat(rows(
            "SELECT tablename || ':' || tableowner FROM pg_tables WHERE schemaname = 'public'"))
        .allMatch(row -> row.endsWith(":an22_owner"));
  }

  @Test
  void everyTableHasStampColumnsAndTrigger() throws Exception {
    List<String> missing =
        rows(TABLES
            + " AND (NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname = 'set_row_stamp')"
            + "   OR (SELECT count(*) FROM pg_attribute a WHERE a.attrelid = c.oid AND NOT a.attisdropped"
            + "        AND a.attname IN ('created_at', 'created_by', 'created_by_kind')) <> 3"
            + "   OR ((SELECT count(*) FROM pg_attribute a WHERE a.attrelid = c.oid AND NOT a.attisdropped"
            + "        AND a.attname IN ('updated_at', 'updated_by', 'updated_by_kind')) <> 3"
            + "       AND c.relname NOT IN (" + quoted(APPEND_ONLY) + ")))");
    assertThat(missing).as("ตารางที่ขาด created_* / updated_* / trigger set_row_stamp").isEmpty();
  }

  @Test
  void everyTenantTableHasForcedRlsWithPolicy() throws Exception {
    assertThat(rows(TENANT_TABLES)).isNotEmpty();
    List<String> missing =
        rows(TENANT_TABLES
            + " AND NOT (c.relrowsecurity AND c.relforcerowsecurity"
            + "  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation'))");
    assertThat(missing).as("ตารางที่มี tenant_id แต่ไม่มี RLS (ENABLE + FORCE + policy)").isEmpty();
  }

  @Test
  void platformTablesTheAppReadsAreUnderRls() throws Exception {
    List<String> missing =
        rows(TABLES + " AND c.relname IN (" + quoted(APP_READABLE_PLATFORM_TABLES) + ")"
            + " AND NOT (c.relrowsecurity AND c.relforcerowsecurity"
            + "  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation'))");
    assertThat(missing).isEmpty();
  }

  @Test
  void everyForeignKeyIsRestrictTenantScopedAndIndexed() throws Exception {
    String fks =
        "SELECT con.conname, con.conrelid, con.conkey, con.confdeltype,"
            + " EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = con.conrelid AND a.attname = 'tenant_id') AS tenant_table,"
            + " (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = con.conrelid AND a.attnum = con.conkey[1]) AS first_col,"
            + " EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = con.conrelid"
            + "   AND (SELECT array_agg(k ORDER BY o) FROM unnest(i.indkey::int2[]) WITH ORDINALITY u(k, o)"
            + "        WHERE o <= array_length(con.conkey, 1)) = con.conkey) AS indexed"
            + " FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE con.contype = 'f' AND n.nspname = 'public'";
    List<String> problems = new ArrayList<>();
    try (Connection c = TestDatabase.superuser(); ResultSet rs = c.createStatement().executeQuery(fks)) {
      while (rs.next()) {
        String name = rs.getString("conname");
        if (!"r".equals(rs.getString("confdeltype"))) {
          problems.add(name + ": ต้อง ON DELETE RESTRICT");
        }
        if (rs.getBoolean("tenant_table") && !"tenant_id".equals(rs.getString("first_col"))) {
          problems.add(name + ": FK ของตารางบริษัทต้องรวม tenant_id เป็นคอลัมน์แรก");
        }
        if (!rs.getBoolean("indexed")) {
          problems.add(name + ": ไม่มี index ฝั่งลูกที่ขึ้นต้นด้วยคอลัมน์ของ FK");
        }
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  void appRoleGrantsStayWithinTheRules() throws Exception {
    List<String> appGrants =
        rows("SELECT table_name || ':' || privilege_type FROM information_schema.role_table_grants"
            + " WHERE grantee = 'an22_app' AND table_schema = 'public'");
    List<String> tenantTables = rows(TENANT_TABLES);

    List<String> outside =
        appGrants.stream()
            .filter(g -> !tenantTables.contains(g.substring(0, g.indexOf(':'))))
            .filter(g -> !APP_PLATFORM_GRANTS.contains(g))
            .toList();
    assertThat(outside).as("an22_app มีสิทธิ์บนตาราง platform เกินที่กำหนด").isEmpty();
    for (String table : APPEND_ONLY) {
      assertThat(appGrants).contains(table + ":INSERT", table + ":SELECT")
          .doesNotContain(table + ":UPDATE", table + ":DELETE", table + ":TRUNCATE");
    }
    assertThat(appGrants).noneMatch(g -> g.endsWith(":TRUNCATE") || g.endsWith(":REFERENCES") || g.endsWith(":TRIGGER"));
  }

  @Test
  void platformRoleCanWriteEveryTable() throws Exception {
    List<String> missing =
        rows(TABLES
            + " AND NOT (has_table_privilege('an22_platform', c.oid, 'SELECT') AND has_table_privilege('an22_platform', c.oid, 'INSERT')"
            + "  AND has_table_privilege('an22_platform', c.oid, 'UPDATE') AND has_table_privilege('an22_platform', c.oid, 'DELETE'))");
    assertThat(missing).isEmpty();
  }

  private static String quoted(List<String> names) {
    return String.join(",", names.stream().map(n -> "'" + n + "'").toList());
  }
}
