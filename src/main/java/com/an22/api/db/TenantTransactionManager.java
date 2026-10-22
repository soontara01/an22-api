package com.an22.api.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * transaction manager ของแอป: ทุก transaction ที่เปิดตั้ง {@code app.*} จาก {@link TxActor#current()} ทันที แบบ
 * {@code set_config(…, true)} (= SET LOCAL — หายเมื่อจบ transaction, ปลอดภัยกับ connection pool / PgBouncer) → service
 * ไม่ต้องจำตั้งเอง; ค่าว่าง = ไม่มีบริษัท (RLS ให้ 0 แถว). {@code readOnly} → {@code SET TRANSACTION READ ONLY} (enforceReadOnly).
 */
public class TenantTransactionManager extends DataSourceTransactionManager {

  static final String SET_ACTOR =
      "SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), set_config('app.actor_kind', ?, true)";

  public TenantTransactionManager(DataSource dataSource) {
    super(dataSource);
    setEnforceReadOnly(true);
  }

  @Override
  protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
    super.prepareTransactionalConnection(con, definition);
    apply(con, TxActor.current());
  }

  private static void apply(Connection con, TxActor actor) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(SET_ACTOR)) {
      ps.setString(1, actor.tenantId() == null ? "" : actor.tenantId().toString());
      ps.setString(2, actor.userId() == null ? "" : actor.userId().toString());
      ps.setString(3, actor.kind());
      ps.execute();
    }
  }
}
