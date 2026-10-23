package com.an22.api.company;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** อ่านทุกตารางของบริษัทเป็น JSON ตามที่เก็บ (E38) — appJdbc ใต้ RLS: เห็นเฉพาะบริษัทของ session. */
@Repository
public class CompanyExportDao {

  /** ตารางที่เป็นการนับภายในของระบบ ไม่ใช่ข้อมูลของบริษัท. */
  private static final List<String> SKIP = List.of("dt_doc_counters");

  private final JdbcClient jdbc;

  CompanyExportDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** export ใหญ่กว่า request ปกติ — เพดานเวลาของ transaction นี้เท่านั้น. */
  public void allowLongQueries(String timeout) {
    jdbc.sql("SELECT set_config('statement_timeout', :t, true)").param("t", timeout).query().singleRow();
  }

  /** ตารางของบริษัท (มี tenant_id) ที่ an22_app อ่านได้ + ใบแจ้งหนี้ค่าบริการ, เรียงตามชื่อ. */
  public List<String> tables() {
    List<String> tables = new java.util.ArrayList<>(jdbc.sql(
            "SELECT DISTINCT c.table_name FROM information_schema.columns c WHERE c.table_schema = 'public'"
                + " AND c.column_name = 'tenant_id' AND c.table_name LIKE 'dt\\_%' AND has_table_privilege(c.table_name, 'SELECT')")
        .query(String.class).list());
    tables.removeAll(SKIP);
    tables.add("dt_billing_invoices");
    tables.sort(null);
    return tables;
  }

  /** แถวของตารางเป็น JSON (ไม่มี tenant_id) เรียงตาม id ถ้ามี. */
  public List<String> rows(String table, boolean hasId) {
    if (!table.matches("dt_[a-z0-9_]+")) {
      throw new IllegalArgumentException("ชื่อตารางไม่ถูกต้อง");
    }
    return jdbc.sql("SELECT (to_jsonb(t) - 'tenant_id')::text FROM " + table + " t" + (hasId ? " ORDER BY t.id" : ""))
        .query(String.class).list();
  }

  public boolean hasId(String table) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :t"
            + " AND column_name = 'id')")
        .param("t", table).query(Boolean.class).single();
  }
}
