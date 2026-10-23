package com.an22.api.audit;

import com.an22.api.model.AuditEvent;
import com.an22.api.rules.AuditRules;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** เขียน dt_audit_log (insert-only) — ใน transaction ของ appTx หรือ platformTx (งาน platform / ก่อน login). */
@Repository
public class AuditDao {

  private static final String INSERT =
      "INSERT INTO dt_audit_log (tenant_id, actor_kind, user_id, user_name, branch_id, terminal_id, action,"
          + " ref_kind, ref_id, ref_label, detail)"
          + " VALUES (:tenant, :kind, :user, :name, :branch, :terminal, :action, :refKind, :refId, :refLabel, :detail)";

  private final JdbcClient app;
  private final JdbcClient platform;

  AuditDao(JdbcClient appJdbc, @Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.app = appJdbc;
    this.platform = platformJdbc;
  }

  public void insert(AuditEntry e) {
    bind(app, e).update();
  }

  public void insertOnPlatform(AuditEntry e) {
    bind(platform, e).update();
  }

  /**
   * {@code filterAuditEvents()}: วันท้องถิ่นของ at ในช่วง + ตัวกรอง; ผู้ดู: admin บริษัทเห็นทั้งหมด ({@code branchIds} = null), admin สาขาเห็นเฉพาะ
   * เหตุการณ์ของสาขาที่ทำงานได้ (ไม่เห็นระดับบริษัท) — ใหม่สุดก่อน ไม่เกิน {@code limit}.
   */
  public List<AuditEvent> list(AuditRules.Query q, List<Long> branchIds, int limit) {
    return app.sql("SELECT id, created_at, actor_kind, user_id, user_name, branch_id, terminal_id, action, ref_kind, ref_id, ref_label,"
            + " detail FROM dt_audit_log WHERE (created_at AT TIME ZONE 'Asia/Bangkok')::date BETWEEN :from AND :to"
            + " AND (CAST(:action AS text) IS NULL OR action = :action)"
            + " AND (CAST(:user AS bigint) IS NULL OR user_id = :user)"
            + " AND (CAST(:branch AS bigint) IS NULL OR branch_id = :branch)"
            + " AND (CAST(:visible AS bigint[]) IS NULL OR branch_id = ANY (CAST(:visible AS bigint[])))"
            + " ORDER BY created_at DESC, id DESC LIMIT :limit")
        .param("from", q.from()).param("to", q.to()).param("action", q.action()).param("user", q.userId())
        .param("branch", q.branchId()).param("visible", branchIds == null ? null : branchIds.toArray(Long[]::new))
        .param("limit", limit)
        .query((rs, i) -> {
          long user = rs.getLong("user_id");
          Long userId = rs.wasNull() ? null : user;
          long branch = rs.getLong("branch_id");
          Long branchId = rs.wasNull() ? null : branch;
          long terminal = rs.getLong("terminal_id");
          Long terminalId = rs.wasNull() ? null : terminal;
          long ref = rs.getLong("ref_id");
          Long refId = rs.wasNull() ? null : ref;
          String kind = rs.getString("ref_kind");
          return new AuditEvent(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(), rs.getString("actor_kind"), userId,
              rs.getString("user_name"), branchId, terminalId, rs.getString("action"),
              kind == null ? null : new AuditEvent.Ref(kind, refId, rs.getString("ref_label")), rs.getString("detail"));
        })
        .list();
  }

  private static JdbcClient.StatementSpec bind(JdbcClient jdbc, AuditEntry e) {
    return jdbc.sql(INSERT)
        .param("tenant", e.tenantId())
        .param("kind", e.actorKind())
        .param("user", e.userId())
        .param("name", e.userName())
        .param("branch", e.branchId())
        .param("terminal", e.terminalId())
        .param("action", e.action())
        .param("refKind", e.refKind())
        .param("refId", e.refId())
        .param("refLabel", e.refLabel())
        .param("detail", e.detail());
  }
}
