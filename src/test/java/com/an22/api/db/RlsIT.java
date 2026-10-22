package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantB;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TenantSql.Actor;
import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;

/** RLS / grant / unique / FK รวม tenant — รันด้วย an22_app ผ่าน PgBouncer เหมือนแอปจริง. */
class RlsIT extends ApiTest {

  private static final long DEMO = 1;
  private static final Actor DEMO_ADMIN = Actor.user(DEMO, 1);

  @Autowired TenantSql sql;
  @Autowired TenantB tenantB;

  private TenantB.Ids b;

  @BeforeEach
  void tenantB() {
    b = tenantB.ids();
  }

  private static String sqlState(Throwable e) {
    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
    return root instanceof SQLException s ? s.getSQLState() : "?";
  }

  private static void assertSqlState(ThrowingCallable call, String state) {
    assertThatThrownBy(call).satisfies(e -> assertThat(sqlState(e)).isEqualTo(state));
  }

  private long count(Actor actor, String table) {
    return sql.as(actor, jdbc -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
  }

  private long countWithoutTenant(String table) {
    return sql.withoutTenant(jdbc -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
  }

  @Test
  void eachTenantSeesOnlyItsOwnRows() {
    assertThat(count(DEMO_ADMIN, "dt_users")).isEqualTo(4);
    assertThat(count(DEMO_ADMIN, "dt_branches")).isEqualTo(2);
    assertThat(count(Actor.user(b.tenantId(), b.ownerId()), "dt_users")).isEqualTo(1);
    assertThat(count(Actor.user(b.tenantId(), b.ownerId()), "dt_branches")).isEqualTo(1);

    Long found =
        sql.as(DEMO_ADMIN, jdbc -> jdbc.sql("SELECT count(*) FROM dt_users WHERE id = :id").param("id", b.ownerId()).query(Long.class).single());
    assertThat(found).as("id ของบริษัท B ใน WHERE ก็ไม่เห็น").isZero();
  }

  @Test
  void noTenantSeesNothingAndPoolDoesNotLeakTenant() {
    // transaction ก่อนหน้าตั้ง tenant แล้ว → transaction ถัดไป (connection เดิมจาก pool / PgBouncer) ต้องไม่ค้าง
    for (int i = 0; i < 10; i++) {
      assertThat(count(DEMO_ADMIN, "dt_users")).isEqualTo(4);
      assertThat(countWithoutTenant("dt_users")).isZero();
      assertThat(countWithoutTenant("dt_company_info")).isZero();
    }
  }

  @Test
  void writingAnotherTenantsRowIsRefused() {
    // INSERT ด้วย tenant_id ของบริษัทอื่น → WITH CHECK ของ policy
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address) VALUES (:t, 'XX', 'x', '00009', 'x')")
                .param("t", b.tenantId())
                .update()),
        "42501");
    // ย้ายแถวของตัวเองไปบริษัทอื่น
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("UPDATE dt_branches SET tenant_id = :t WHERE id = 2").param("t", b.tenantId()).update()),
        "42501");
    // แก้ / ลบแถวของบริษัทอื่น = ไม่เจอแถว
    sql.rolledBack(DEMO_ADMIN, jdbc -> {
      assertThat(jdbc.sql("UPDATE dt_users SET name = 'x' WHERE id = :id").param("id", b.ownerId()).update()).isZero();
      assertThat(jdbc.sql("DELETE FROM dt_user_branches WHERE tenant_id = :t").param("t", b.tenantId()).update()).isZero();
    });
  }

  @Test
  void auditLogIsInsertOnlyForTheApp() {
    sql.rolledBack(DEMO_ADMIN, jdbc ->
        assertThat(jdbc.sql(
                "INSERT INTO dt_audit_log (tenant_id, actor_kind, user_id, user_name, action) VALUES (1, 'user', 1, 'admin', 'login')")
            .update()).isEqualTo(1));
    assertSqlState(() -> sql.rolledBack(DEMO_ADMIN, jdbc -> jdbc.sql("UPDATE dt_audit_log SET detail = 'x'").update()), "42501");
    assertSqlState(() -> sql.rolledBack(DEMO_ADMIN, jdbc -> jdbc.sql("DELETE FROM dt_audit_log").update()), "42501");
  }

  @Test
  void appRoleIsNotOwnerAndDoesNotBypassRls() {
    var row =
        sql.as(DEMO_ADMIN, jdbc -> jdbc.sql(
                "SELECT current_user AS who, r.rolbypassrls AS bypass, r.rolsuper AS su,"
                    + " pg_has_role(current_user, 'an22_owner', 'MEMBER') AS owner_member"
                    + " FROM pg_roles r WHERE r.rolname = current_user")
            .query()
            .singleRow());
    assertThat(row).containsEntry("who", "an22_app").containsEntry("bypass", false).containsEntry("su", false)
        .containsEntry("owner_member", false);
    // เปลี่ยนโครงตาราง / ปิด RLS ไม่ได้
    assertSqlState(() -> sql.rolledBack(DEMO_ADMIN, jdbc -> jdbc.sql("ALTER TABLE dt_users DISABLE ROW LEVEL SECURITY").update()), "42501");
    // ตาราง platform อื่นนอกจาก dt_companies ไม่มีสิทธิ์
    assertSqlState(() -> sql.as(DEMO_ADMIN, jdbc -> jdbc.sql("SELECT count(*) FROM dt_platform_admins").query(Long.class).single()), "42501");
  }

  @Test
  void usernameIsUniquePerTenantOnly() {
    // บริษัท B มี admin เหมือน DEMO อยู่แล้ว (TenantB) — ซ้ำในบริษัทเดียวกันไม่ได้
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, role, password_hash) VALUES (1, 'admin', 'x', 'user', 'x')").update()),
        "23505");
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, email, role, password_hash) VALUES (1, 'other', 'x', 'ADMIN@example.com', 'user', 'x')").update()),
        "23505");
  }

  @Test
  void secondOwnerIsRefused() {
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, email, role, all_branches, password_hash)"
                    + " VALUES (1, 'owner2', 'x', 'o2@example.com', 'owner', true, 'x')")
                .update()),
        "23505");
  }

  @Test
  void referencingAnotherTenantsBranchIsRefused() {
    // FK รวม tenant: (1, branch ของ B) ไม่มีใน dt_branches (tenant_id, id)
    assertSqlState(
        () -> sql.rolledBack(DEMO_ADMIN, jdbc ->
            jdbc.sql("INSERT INTO dt_user_branches (tenant_id, user_id, branch_id) VALUES (1, 4, :branch)")
                .param("branch", b.branchId())
                .update()),
        "23503");
    sql.rolledBack(DEMO_ADMIN, jdbc ->
        assertThat(jdbc.sql("INSERT INTO dt_user_branches (tenant_id, user_id, branch_id) VALUES (1, 4, 2)").update()).isEqualTo(1));
  }

  @Test
  void referencedRowsCannotBeDeleted() {
    // ON DELETE RESTRICT: สาขาที่ผู้ใช้อ้างอยู่ลบไม่ได้
    assertSqlState(() -> sql.rolledBack(DEMO_ADMIN, jdbc -> jdbc.sql("DELETE FROM dt_branches WHERE id = 1").update()), "23001");
  }
}
