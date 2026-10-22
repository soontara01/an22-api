package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.an22.api.support.ApiTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** TenantTransactionManager ตั้ง app.* จาก TxActor ทุก transaction; TxGuard ห้าม query นอก transaction. */
class TenantTransactionIT extends ApiTest {

  private static final String SETTINGS =
      "SELECT current_setting('app.tenant_id', true) AS tenant, current_setting('app.user_id', true) AS usr,"
          + " current_setting('app.actor_kind', true) AS kind, (SELECT count(*) FROM dt_users) AS users";

  @Autowired JdbcClient appJdbc;
  @Autowired PlatformTransactionManager appTx;

  @AfterEach
  void clear() {
    TxActor.clear();
  }

  private Map<String, Object> settings() {
    return new TransactionTemplate(appTx).execute(s -> appJdbc.sql(SETTINGS).query().singleRow());
  }

  @Test
  void everyTransactionGetsTheCurrentActor() {
    TxActor.set(TxActor.user(1, 2));
    assertThat(settings()).containsEntry("tenant", "1").containsEntry("usr", "2").containsEntry("kind", "user")
        .containsEntry("users", 4L);

    TxActor.clear();
    // connection เดิมจาก pool / PgBouncer: ไม่มีค่าของ transaction ก่อนค้าง
    for (int i = 0; i < 5; i++) {
      assertThat(settings()).containsEntry("tenant", "").containsEntry("usr", "").containsEntry("kind", "system")
          .containsEntry("users", 0L);
    }
  }

  @Test
  void rowsWrittenInATransactionAreStampedWithTheActor() {
    TxActor.set(TxActor.user(1, 4));
    TransactionTemplate tx = new TransactionTemplate(appTx);
    tx.executeWithoutResult(s -> {
      s.setRollbackOnly();
      appJdbc.sql("UPDATE dt_branches SET phone = phone WHERE id = 2").update();
      assertThat(appJdbc.sql("SELECT updated_by FROM dt_branches WHERE id = 2").query(Long.class).single()).isEqualTo(4L);
    });
  }

  @Test
  void queryOutsideATransactionFails() {
    TxActor.set(TxActor.user(1, 1));
    assertThatThrownBy(() -> appJdbc.sql("SELECT count(*) FROM dt_users").query(Long.class).single())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining("query นอก transaction");
  }
}
