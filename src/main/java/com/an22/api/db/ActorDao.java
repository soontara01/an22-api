package com.an22.api.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * เปลี่ยนผู้ทำกลาง transaction (รู้ตัวหลังเปิด transaction แล้ว เช่น login / signup ที่ใช้ platformTx) — ให้ trigger
 * {@code set_row_stamp()} และ RLS ของ statement ถัดไปเห็นผู้ทำที่ถูก. ค่าเป็น SET LOCAL หายเมื่อจบ transaction.
 */
@Repository
public class ActorDao {

  private final JdbcClient appJdbc;
  private final JdbcClient platformJdbc;

  ActorDao(JdbcClient appJdbc, @Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.appJdbc = appJdbc;
    this.platformJdbc = platformJdbc;
  }

  /** ใน transaction ของ appTx. */
  public void become(TxActor actor) {
    set(appJdbc, actor);
  }

  /** ใน transaction ของ platformTx. */
  public void becomeOnPlatform(TxActor actor) {
    set(platformJdbc, actor);
  }

  private static void set(JdbcClient jdbc, TxActor actor) {
    jdbc.sql(TenantTransactionManager.SET_ACTOR)
        .param(actor.tenantId() == null ? "" : actor.tenantId().toString())
        .param(actor.userId() == null ? "" : actor.userId().toString())
        .param(actor.kind())
        .query()
        .singleRow();
  }
}
