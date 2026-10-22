package com.an22.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ApplicationIT extends ApiTest {

  @Autowired JdbcClient appJdbc;

  @Autowired
  @Qualifier("platformJdbc")
  JdbcClient platformJdbc;

  @Autowired PlatformTransactionManager appTx;

  @Autowired
  @Qualifier("platformTx")
  PlatformTransactionManager platformTx;

  @Test
  void healthzNeedsNoLoginAndNoDatabase() throws Exception {
    mvc.perform(get("/healthz"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"ok\"}", true))
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void readyzPingsDatabase() throws Exception {
    mvc.perform(get("/readyz")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}", true));
  }

  @Test
  void requestIdFromWebIsKept() throws Exception {
    mvc.perform(get("/healthz").header("X-Request-Id", "web-abc_1"))
        .andExpect(header().string("X-Request-Id", "web-abc_1"));
  }

  @Test
  void appAndPlatformConnectThroughPgBouncerAsTheirRoles() {
    assertThat(TestDatabase.poolerUrl()).contains(":" + TestDatabase.PGBOUNCER.getMappedPort(6432) + "/");

    Map<String, Object> app = inTx(appTx, () -> appJdbc.sql(WHO_AM_I).query().singleRow());
    assertThat(app).containsEntry("usr", "an22_app").containsEntry("bypass", false).containsEntry("ro", "off");

    Map<String, Object> platform = inTx(platformTx, () -> platformJdbc.sql(WHO_AM_I).query().singleRow());
    assertThat(platform).containsEntry("usr", "an22_platform").containsEntry("bypass", true);
  }

  @Test
  void readOnlyTransactionIsReadOnlyInPostgres() {
    TransactionTemplate tx = new TransactionTemplate(appTx);
    tx.setReadOnly(true);
    String readOnly = tx.execute(s -> appJdbc.sql("SHOW transaction_read_only").query(String.class).single());
    assertThat(readOnly).isEqualTo("on");
    // transaction ถัดไปบน pool เดิมไม่ติด read only ค้าง (transaction pooling)
    assertThat(inTx(appTx, () -> appJdbc.sql("SHOW transaction_read_only").query(String.class).single()))
        .isEqualTo("off");
  }

  /** session DB ได้ TimeZone ของ JVM (Asia/Bangkok) ผ่าน PgBouncer — now()::date / current_date ใน SQL = วันไทย. */
  @Test
  void databaseSessionUsesBangkokTime() {
    assertThat(inTx(appTx, () -> appJdbc.sql("SHOW TimeZone").query(String.class).single())).isEqualTo("Asia/Bangkok");
  }

  /** pgJDBC เปลี่ยนเป็น server-side prepared statement หลังรันซ้ำ 5 ครั้ง — ต้องผ่าน PgBouncer ได้ข้ามหลาย transaction. */
  @Test
  void repeatedPreparedStatementsWorkThroughTransactionPooling() {
    for (int i = 0; i < 30; i++) {
      int n = i;
      Integer result = inTx(appTx, () -> appJdbc.sql("SELECT ?::int + 1").param(n).query(Integer.class).single());
      assertThat(result).isEqualTo(n + 1);
    }
  }

  private static final String WHO_AM_I =
      """
      SELECT current_user AS usr,
             (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) AS bypass,
             current_setting('transaction_read_only') AS ro
      """;

  private static <T> T inTx(PlatformTransactionManager manager, java.util.function.Supplier<T> work) {
    return new TransactionTemplate(manager).execute(s -> work.get());
  }
}
