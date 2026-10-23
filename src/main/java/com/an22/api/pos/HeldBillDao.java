package com.an22.api.pos;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_held_bills ของบริษัทใน transaction — payload เข้ารหัสแล้ว (service เข้า / ถอดรหัส). */
@Repository
public class HeldBillDao {

  /** แถวที่ยังเข้ารหัสอยู่. */
  public record Row(long id, Instant heldAt, byte[] payload) {}

  private final JdbcClient jdbc;

  HeldBillDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** บิลพักของสาขา + เครื่อง เก่าก่อน. */
  public List<Row> list(long branchId, int terminalId) {
    return jdbc.sql("SELECT id, held_at, payload_enc FROM dt_held_bills WHERE branch_id = :branch AND terminal_id = :terminal"
            + " ORDER BY held_at, id")
        .param("branch", branchId).param("terminal", terminalId)
        .query((ResultSet rs, int i) -> new Row(rs.getLong("id"), rs.getTimestamp("held_at").toInstant(), rs.getBytes("payload_enc")))
        .list();
  }

  public long insert(long branchId, int terminalId, Instant heldAt, byte[] payload) {
    return jdbc.sql("INSERT INTO dt_held_bills (tenant_id, branch_id, terminal_id, held_at, payload_enc)"
            + " VALUES (app_tenant_id(), :branch, :terminal, :heldAt, :payload) RETURNING id")
        .param("branch", branchId).param("terminal", terminalId).param("heldAt", Timestamp.from(heldAt)).param("payload", payload)
        .query(Long.class).single();
  }

  /** ลบบิลของสาขา + เครื่องนี้ — false = ไม่มี (ของที่อื่น = ไม่มี). */
  public boolean delete(long id, long branchId, int terminalId) {
    return jdbc.sql("DELETE FROM dt_held_bills WHERE id = :id AND branch_id = :branch AND terminal_id = :terminal")
        .param("id", id).param("branch", branchId).param("terminal", terminalId)
        .update() > 0;
  }
}
