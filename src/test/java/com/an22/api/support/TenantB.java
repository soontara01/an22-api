package com.an22.api.support;

import org.springframework.stereotype.Component;

/**
 * บริษัทที่ 2 สำหรับ test ข้าม tenant (สร้างครั้งเดียวต่อ DB ด้วย an22_platform): สาขา 1, owner ชื่อ {@code admin}
 * (ชื่อเดียวกับ DEMO — username ซ้ำได้ข้ามบริษัท).
 */
@Component
public class TenantB {

  public static final String CODE = "TENB";

  public record Ids(long tenantId, long branchId, long ownerId) {}

  private final TenantSql sql;
  private Ids ids;

  TenantB(TenantSql sql) {
    this.sql = sql;
  }

  public synchronized Ids ids() {
    if (ids == null) {
      ids = sql.platform(jdbc -> {
        Long existing =
            jdbc.sql("SELECT id FROM dt_companies WHERE code = :code").param("code", CODE).query(Long.class).optional().orElse(null);
        if (existing == null) {
          long tenant =
              jdbc.sql("INSERT INTO dt_companies (code, plan_id, next_invoice_on) VALUES (:code, 'starter', '9999-12-31') RETURNING id")
                  .param("code", CODE)
                  .query(Long.class)
                  .single();
          jdbc.sql("INSERT INTO dt_company_info (tenant_id, name) VALUES (:t, 'บริษัท บี จำกัด')").param("t", tenant).update();
          jdbc.sql(
                  "INSERT INTO dt_branches (tenant_id, code, name, branch_no, address)"
                      + " VALUES (:t, 'HQ', 'ร้านบี', '00000', 'ที่อยู่บี')")
              .param("t", tenant)
              .update();
          jdbc.sql(
                  "INSERT INTO dt_users (tenant_id, username, name, email, role, all_branches, password_hash)"
                      + " VALUES (:t, 'admin', 'Admin B', 'b@example.com', 'owner', true, 'x')")
              .param("t", tenant)
              .update();
          existing = tenant;
        }
        long tenant = existing;
        long branch = jdbc.sql("SELECT id FROM dt_branches WHERE tenant_id = :t").param("t", tenant).query(Long.class).single();
        long owner =
            jdbc.sql("SELECT id FROM dt_users WHERE tenant_id = :t AND role = 'owner'").param("t", tenant).query(Long.class).single();
        return new Ids(tenant, branch, owner);
      });
    }
    return ids;
  }
}
