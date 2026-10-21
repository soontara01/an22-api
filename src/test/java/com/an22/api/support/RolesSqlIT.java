package com.an22.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/** db/roles.sql (รันโดย docker/initdb.sh ด้วย admin ที่ไม่ใช่ superuser แบบ Azure) ได้ role / database ตามแผน. */
class RolesSqlIT {

  @BeforeAll
  static void start() {
    TestDatabase.start();
  }

  private record Role(boolean superuser, boolean bypassRls, boolean createDb, boolean createRole, String config) {}

  private static Map<String, Role> roles() throws Exception {
    Map<String, Role> roles = new HashMap<>();
    try (Connection c = TestDatabase.superuser();
        ResultSet rs =
            c.createStatement()
                .executeQuery(
                    "SELECT rolname, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, array_to_string(rolconfig, ',')"
                        + " FROM pg_roles WHERE rolname IN ('an22_owner', 'an22_app', 'an22_platform', 'azure_admin')")) {
      while (rs.next()) {
        roles.put(
            rs.getString(1),
            new Role(rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6)));
      }
    }
    return roles;
  }

  @Test
  void rolesMatchThePlan() throws Exception {
    Map<String, Role> roles = roles();

    assertThat(roles.get("azure_admin").superuser()).as("admin ไม่ใช่ superuser (เหมือน Azure)").isFalse();
    assertThat(roles.get("an22_app")).isEqualTo(new Role(false, false, false, false,
        "statement_timeout=30s,idle_in_transaction_session_timeout=60s"));
    assertThat(roles.get("an22_platform")).isEqualTo(new Role(false, true, false, false,
        "statement_timeout=30s,idle_in_transaction_session_timeout=60s"));
    assertThat(roles.get("an22_owner")).isEqualTo(new Role(false, false, false, false, null));
  }

  @Test
  void databaseIsOwnedByOwnerAndOnlyRolesCanConnect() throws Exception {
    try (Connection c = TestDatabase.superuser();
        ResultSet rs =
            c.createStatement()
                .executeQuery(
                    "SELECT pg_get_userbyid(datdba),"
                        + " has_database_privilege('an22_app', datname, 'CONNECT'),"
                        + " has_database_privilege('an22_app', datname, 'TEMP'),"
                        + " has_database_privilege('an22_platform', datname, 'CONNECT'),"
                        + " has_database_privilege('azure_admin', datname, 'CONNECT') AND NOT pg_has_role('azure_admin', datdba, 'USAGE')"
                        + " FROM pg_database WHERE datname = 'an22'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("an22_owner");
      assertThat(rs.getBoolean(2)).isTrue();
      assertThat(rs.getBoolean(3)).as("ไม่มี temp table").isFalse();
      assertThat(rs.getBoolean(4)).isTrue();
      assertThat(rs.getBoolean(5)).as("PUBLIC ไม่มีสิทธิ์ CONNECT").isFalse();
    }
  }

  @Test
  void rerunningRolesSqlChangesNothing() throws Exception {
    Map<String, Role> before = roles();
    ExecResult result =
        TestDatabase.POSTGRES.execInContainer(
            "psql", "-X", "-q", "-U", TestDatabase.ADMIN_USER, "-d", "postgres",
            "-v", "owner_password=" + TestDatabase.OWNER_PASSWORD,
            "-v", "app_password=" + TestDatabase.APP_PASSWORD,
            "-v", "platform_password=" + TestDatabase.PLATFORM_PASSWORD,
            "-f", "/db/roles.sql");

    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    assertThat(roles()).isEqualTo(before);
  }

  @Test
  void missingPasswordStopsWithoutRunning() throws Exception {
    ExecResult result =
        TestDatabase.POSTGRES.execInContainer(
            "psql", "-X", "-q", "-U", TestDatabase.ADMIN_USER, "-d", "postgres", "-f", "/db/roles.sql");

    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("ต้องส่ง -v owner_password");
  }
}
