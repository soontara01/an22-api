package com.an22.api.user;

import com.an22.api.model.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_users + dt_user_branches ของบริษัทใน transaction (RLS) — ไม่เคยคืน hash รหัสผ่านนอกจาก {@link #passwordHash}. */
@Repository
public class UserDao {

  /** ผู้ใช้ + สาขาที่เข้าได้ (ว่างเมื่อทุกสาขา) — ใช้ร่วมกับ SessionDao. */
  public static final String SELECT =
      "SELECT u.id, u.username, u.name, u.email, u.role, u.active, u.all_branches, u.created_at, u.password_hash,"
          + " ARRAY(SELECT b.branch_id FROM dt_user_branches b WHERE b.tenant_id = u.tenant_id AND b.user_id = u.id"
          + "       ORDER BY b.branch_id) AS branch_ids"
          + " FROM dt_users u";

  private final JdbcClient jdbc;

  UserDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public List<User> list() {
    return jdbc.sql(SELECT + " ORDER BY u.id").query(UserDao::row).list();
  }

  public Optional<User> find(long id) {
    return jdbc.sql(SELECT + " WHERE u.id = :id").param("id", id).query(UserDao::row).optional();
  }

  public String passwordHash(long id) {
    return jdbc.sql("SELECT password_hash FROM dt_users WHERE id = :id").param("id", id).query(String.class).single();
  }

  /** @return id ใหม่ */
  public long insert(User u, String passwordHash) {
    long id =
        jdbc.sql(
                "INSERT INTO dt_users (tenant_id, username, name, email, role, active, all_branches, password_hash)"
                    + " VALUES (app_tenant_id(), :username, :name, :email, :role, :active, :all, :hash) RETURNING id")
            .param("username", u.username())
            .param("name", u.name())
            .param("email", u.email())
            .param("role", u.role())
            .param("active", u.active())
            .param("all", u.allBranches())
            .param("hash", passwordHash)
            .query(Long.class)
            .single();
    setBranches(id, u.branchIds());
    return id;
  }

  public void update(User u) {
    jdbc.sql(
            "UPDATE dt_users SET username = :username, name = :name, email = :email, role = :role, active = :active,"
                + " all_branches = :all WHERE id = :id")
        .param("username", u.username())
        .param("name", u.name())
        .param("email", u.email())
        .param("role", u.role())
        .param("active", u.active())
        .param("all", u.allBranches())
        .param("id", u.id())
        .update();
    setBranches(u.id(), u.branchIds());
  }

  public void setPassword(long id, String passwordHash) {
    jdbc.sql("UPDATE dt_users SET password_hash = :hash WHERE id = :id").param("hash", passwordHash).param("id", id).update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_user_branches WHERE user_id = :id").param("id", id).update();
    jdbc.sql("DELETE FROM dt_users WHERE id = :id").param("id", id).update();
  }

  /** แทนสาขาของผู้ใช้ทั้งชุด (ว่าง = ไม่มี / ทุกสาขา). */
  private void setBranches(long userId, List<Long> branchIds) {
    jdbc.sql("DELETE FROM dt_user_branches WHERE user_id = :id AND NOT (branch_id = ANY (:keep))")
        .param("id", userId)
        .param("keep", branchIds.toArray(Long[]::new))
        .update();
    jdbc.sql(
            "INSERT INTO dt_user_branches (tenant_id, user_id, branch_id)"
                + " SELECT app_tenant_id(), :id, b FROM unnest(:ids) AS b ON CONFLICT DO NOTHING")
        .param("id", userId)
        .param("ids", branchIds.toArray(Long[]::new))
        .update();
  }

  public static User row(ResultSet rs, int i) throws SQLException {
    boolean all = rs.getBoolean("all_branches");
    List<Long> branchIds = all ? List.of() : List.of((Long[]) rs.getArray("branch_ids").getArray());
    return new User(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getString("role"),
        rs.getBoolean("active"),
        branchIds,
        all,
        rs.getTimestamp("created_at").toInstant());
  }
}
