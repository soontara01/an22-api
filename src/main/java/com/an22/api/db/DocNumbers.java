package com.an22.api.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * เลขเอกสารของบริษัท ({@code dt_doc_counters}) — เรียกจาก service ใน transaction เดียวกับเอกสาร หลังตรวจกฎผ่านหมดแล้ว:
 * แถวตัวนับถูกล็อกจน commit → ไม่ซ้ำแม้หลาย instance / ผ่าน PgBouncer, rollback แล้วเลขไม่ข้าม (ไม่ใช้ SEQUENCE).
 */
@Repository
public class DocNumbers {

  private final JdbcClient jdbc;

  DocNumbers(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** เลขถัดไปของ prefix (prefix ใหม่เริ่ม 1), เช่น ("TR-HQ-20261023-", 4) → {@code TR-HQ-20261023-0001}. */
  public String next(String prefix, int width) {
    int n = jdbc.sql(
            "INSERT INTO dt_doc_counters (tenant_id, prefix, last_no) VALUES (app_tenant_id(), :prefix, 1)"
                + " ON CONFLICT (tenant_id, prefix) DO UPDATE SET last_no = dt_doc_counters.last_no + 1 RETURNING last_no")
        .param("prefix", prefix)
        .query(Integer.class)
        .single();
    String digits = Integer.toString(n);
    return prefix + "0".repeat(Math.max(0, width - digits.length())) + digits;
  }
}
