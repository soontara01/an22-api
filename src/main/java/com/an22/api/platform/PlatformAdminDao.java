package com.an22.api.platform;

import com.an22.api.model.PlatformAdmin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformAdminDao {

  /** admin + hash รหัสผ่าน (ไม่เคยออกนอก service). */
  public record AdminRow(PlatformAdmin admin, String passwordHash) {}

  private static final String SELECT = "SELECT id, username, name, password_hash FROM dt_platform_admins";

  private final JdbcClient jdbc;

  PlatformAdminDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  public Optional<AdminRow> byId(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(PlatformAdminDao::row).optional();
  }

  /** {@code username} ตัวเล็กแล้ว. */
  public Optional<AdminRow> byUsername(String username) {
    return jdbc.sql(SELECT + " WHERE username = :u").param("u", username).query(PlatformAdminDao::row).optional();
  }

  private static AdminRow row(ResultSet rs, int i) throws SQLException {
    return new AdminRow(
        new PlatformAdmin(rs.getLong("id"), rs.getString("username"), rs.getString("name")), rs.getString("password_hash"));
  }
}
