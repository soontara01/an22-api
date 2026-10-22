package com.an22.api.support;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** service ของ test: อ่านค่า app.* ที่ TenantTransactionManager ตั้งใน transaction ของ request + จำนวนผู้ใช้ที่ RLS ให้เห็น. */
@Service
public class ProbeService {

  private final JdbcClient jdbc;

  ProbeService(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> dbSession() {
    return jdbc.sql(
            "SELECT current_setting('app.tenant_id', true) AS tenant, current_setting('app.user_id', true) AS \"user\","
                + " current_setting('app.actor_kind', true) AS kind, (SELECT count(*) FROM dt_users) AS users,"
                + " (SELECT count(*) FROM dt_companies) AS companies")
        .query()
        .singleRow();
  }
}
