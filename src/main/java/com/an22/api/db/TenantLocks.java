package com.an22.api.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ล็อกงานเขียนของบริษัทใน transaction ปัจจุบัน ({@code pg_advisory_xact_lock} — ปล่อยเองตอนจบ transaction, ใช้ผ่าน PgBouncer ได้)
 * — กฎที่นับทั้งบริษัท (เพดานแพ็กเกจ, ต้องเหลือ admin ≥ 1, ต้องเหลือสาขาที่ใช้งาน ≥ 1) ไม่หลุดเมื่อมี request พร้อมกันหลาย instance.
 */
@Repository
public class TenantLocks {

  /** ล็อกการเปลี่ยนผู้ใช้ / สาขา / เครื่อง POS ของบริษัท (ทุกอย่างที่แพ็กเกจนับ) — เรียกก่อนอ่านข้อมูลที่จะตรวจกฎ. */
  public static final String ACCOUNTS = "accounts";
  /** ล็อกการเปิดกะ / ปิดระบบกะ / ปิดสาขา-เครื่องที่อาจมีกะเปิด (เรียงหลัง ACCOUNTS เสมอ — เปิดกะไม่ถือ ACCOUNTS). */
  public static final String SHIFTS = "shifts";

  private final JdbcClient jdbc;

  TenantLocks(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public void lock(String purpose) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:purpose || ':' || app_tenant_id(), 0))")
        .param("purpose", purpose)
        .query()
        .singleRow();
  }
}
