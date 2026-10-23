package com.an22.api.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** จำกัดขนาดคำตอบ (ตกลง 2026-10-23): ช่วงวันที่ ≤ 366 วัน, เพดาน 20,000 แถว → 400 ไม่ตัดเงียบ. */
class ListLimitsIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  @Test
  void stockCardNeedsADateRangeOfAtMost366Days() throws Exception {
    String admin = login("DEMO", "admin", "admin");
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    mvc.perform(as(admin, get("/products/2/movements")).header("X-Branch-Id", 1)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"), true));
    mvc.perform(as(admin, get("/products/2/movements").param("from", today.minusDays(366).toString()).param("to", today.toString()))
            .header("X-Branch-Id", 1))
        .andExpect(content().json(message("ช่วงวันที่ต้องไม่เกิน 366 วัน"), true));
    mvc.perform(as(admin, get("/products/2/movements").param("from", "2026-13-01").param("to", today.toString()))
            .header("X-Branch-Id", 1))
        .andExpect(content().json(message("รูปแบบวันที่ต้องเป็น YYYY-MM-DD"), true));
    mvc.perform(as(admin, lastYear(get("/products/2/movements"))).header("X-Branch-Id", 1)).andExpect(status().isOk());
  }

  @Test
  void listsOverTheCeilingAreRefusedNotCut() throws Exception {
    Company c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    sql.as(TenantSql.Actor.system(c.id()), jdbc -> jdbc.sql(
            "INSERT INTO dt_products (tenant_id, sku, name) SELECT :t, 'S' || n, 'สินค้า ' || n FROM generate_series(1, 20000) AS n")
        .param("t", c.id()).update());
    mvc.perform(as(owner, get("/products")).header("X-Branch-Id", c.hq())).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20_000));
    sql.as(TenantSql.Actor.system(c.id()), jdbc -> jdbc.sql("INSERT INTO dt_products (tenant_id, sku, name) VALUES (:t, 'S-LAST', 'เกิน')")
        .param("t", c.id()).update());
    mvc.perform(as(owner, get("/products")).header("X-Branch-Id", c.hq())).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ข้อมูลมากกว่า 20,000 รายการ กรุณากรองให้แคบลง"), true));
  }
}
