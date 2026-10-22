package com.an22.api.audit;

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
