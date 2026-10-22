package com.an22.api.pos;

import com.an22.api.model.PosTerminal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_pos_terminals ของบริษัทใน transaction — id = เลขเครื่อง (ไม่ซ้ำในบริษัท). */
@Repository
public class PosTerminalDao {

  private static final String SELECT = "SELECT id, name, pos_id, branch_id, active FROM dt_pos_terminals";

  private final JdbcClient jdbc;

  PosTerminalDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public List<PosTerminal> list() {
    return jdbc.sql(SELECT + " ORDER BY id").query(PosTerminalDao::row).list();
  }

  public Optional<PosTerminal> find(int id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(PosTerminalDao::row).optional();
  }

  public void insert(PosTerminal t) {
    jdbc.sql(
            "INSERT INTO dt_pos_terminals (tenant_id, id, name, pos_id, branch_id, active)"
                + " VALUES (app_tenant_id(), :id, :name, :posId, :branch, :active)")
        .param("id", t.id()).param("name", t.name()).param("posId", t.posId()).param("branch", t.branchId())
        .param("active", t.active())
        .update();
  }

  public void update(PosTerminal t) {
    jdbc.sql("UPDATE dt_pos_terminals SET name = :name, pos_id = :posId, branch_id = :branch, active = :active WHERE id = :id")
        .param("id", t.id()).param("name", t.name()).param("posId", t.posId()).param("branch", t.branchId())
        .param("active", t.active())
        .update();
  }

  public void delete(int id) {
    jdbc.sql("DELETE FROM dt_pos_terminals WHERE id = :id").param("id", id).update();
  }

  private static PosTerminal row(ResultSet rs, int i) throws SQLException {
    return new PosTerminal(rs.getInt("id"), rs.getString("name"), rs.getString("pos_id"), rs.getLong("branch_id"),
        rs.getBoolean("active"));
  }
}
