package com.an22.api.support;

import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * รัน SQL ใน transaction ของแอป (an22_app ผ่าน PgBouncer) โดยตั้ง app.tenant_id / app.user_id / app.actor_kind
 * แบบ SET LOCAL เหมือนที่ TenantTransactionManager (ขั้น 2) จะทำ — ใช้ใน test ของ schema / RLS.
 */
@Component
public class TenantSql {

  /** ผู้ทำ: kind = user / platform / system, userId = null ได้ */
  public record Actor(long tenantId, Long userId, String kind) {
    public static Actor user(long tenantId, long userId) {
      return new Actor(tenantId, userId, "user");
    }

    public static Actor system(long tenantId) {
      return new Actor(tenantId, null, "system");
    }
  }

  private final JdbcClient app;
  private final JdbcClient platform;
  private final PlatformTransactionManager appTx;
  private final PlatformTransactionManager platformTx;

  TenantSql(
      JdbcClient appJdbc,
      @Qualifier("platformJdbc") JdbcClient platformJdbc,
      PlatformTransactionManager appTx,
      @Qualifier("platformTx") PlatformTransactionManager platformTx) {
    this.app = appJdbc;
    this.platform = platformJdbc;
    this.appTx = appTx;
    this.platformTx = platformTx;
  }

  /** an22_app ในนาม actor — commit เมื่อจบ. */
  public <T> T as(Actor actor, Function<JdbcClient, T> work) {
    return new TransactionTemplate(appTx).execute(s -> work.apply(set(app, actor)));
  }

  /** an22_app ในนาม actor — rollback เสมอ (test ที่แก้ข้อมูลไม่ทิ้งของไว้ให้ test อื่น). */
  public void rolledBack(Actor actor, Consumer<JdbcClient> work) {
    new TransactionTemplate(appTx)
        .executeWithoutResult(
            s -> {
              s.setRollbackOnly();
              work.accept(set(app, actor));
            });
  }

  /** an22_app โดยไม่ตั้งบริษัท (เหมือน request ที่ลืมตั้ง). */
  public <T> T withoutTenant(Function<JdbcClient, T> work) {
    return new TransactionTemplate(appTx).execute(s -> work.apply(app));
  }

  /** an22_platform (BYPASSRLS) ในนาม system. */
  public <T> T platform(Function<JdbcClient, T> work) {
    return new TransactionTemplate(platformTx)
        .execute(
            s -> {
              platform.sql("SELECT set_config('app.actor_kind', 'system', true)").query().singleRow();
              return work.apply(platform);
            });
  }

  private static JdbcClient set(JdbcClient jdbc, Actor actor) {
    jdbc.sql(
            "SELECT set_config('app.tenant_id', :tenant, true), set_config('app.user_id', :user, true),"
                + " set_config('app.actor_kind', :kind, true)")
        .param("tenant", Long.toString(actor.tenantId()))
        .param("user", actor.userId() == null ? "" : actor.userId().toString())
        .param("kind", actor.kind())
        .query()
        .singleRow();
    return jdbc;
  }
}
