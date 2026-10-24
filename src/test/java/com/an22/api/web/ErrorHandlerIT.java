package com.an22.api.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.test.context.support.WithMockUser;

class ErrorHandlerIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  @Test
  void noTokenIs401WithSessionMessage() throws Exception {
    mvc.perform(get("/sales"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่\"}", true));
  }

  @Test
  @WithMockUser
  void unknownPathIs404LikeMock() throws Exception {
    mvc.perform(get("/no-such/thing"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"message\":\"ไม่มี endpoint GET /no-such/thing\"}", true));
  }

  @Test
  @WithMockUser
  void unsupportedMethodIs404LikeMock() throws Exception {
    mvc.perform(delete("/healthz"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"message\":\"ไม่มี endpoint DELETE /healthz\"}", true));
  }

  @Test
  @WithMockUser
  void unreadableJsonIs400() throws Exception {
    mvc.perform(post("/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content("{\"a\":"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"ข้อมูลที่ส่งมาไม่ถูกต้อง\"}", true));
  }

  @Test
  @WithMockUser
  void apiExceptionKeepsItsStatusAndMessage() throws Exception {
    mvc.perform(post("/test-probe/reject").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"ปฏิเสธตามกฎ\"}", true));
  }

  @Test
  @WithMockUser
  void unexpectedErrorIs500WithoutDetails() throws Exception {
    mvc.perform(get("/test-probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().json("{\"message\":\"ระบบขัดข้อง กรุณาลองใหม่\"}", true));
  }

  @Test
  @WithMockUser
  void dbTimeoutIs503() throws Exception {
    mvc.perform(get("/test-probe/slow"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().json("{\"message\":\"ระบบใช้เวลานานเกินไป กรุณาลองใหม่หรือลดช่วงข้อมูล\"}", true));
  }

  @Test
  @WithMockUser
  void jsonKeepsNullsAndWritesIsoDates() throws Exception {
    mvc.perform(get("/test-probe/nulls"))
        .andExpect(status().isOk())
        .andExpect(content().string("{\"empty\":null,\"at\":\"2026-10-21T03:04:05Z\"}"));
  }

  // ---------- DB ปฏิเสธค่าที่กฎไม่ได้ตรวจ → 400 (E2E B2 / B8 / B9) ----------

  private static final String BAD_REQUEST = "{\"message\":\"ข้อมูลที่ส่งมาไม่ถูกต้อง\"}";

  private long rows(Company c, String table) {
    return sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", c.id()).query(Long.class).single());
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  @Test
  void checkConstraintIs400() throws Exception {
    Company c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/suppliers"), Map.of("code", "S1", "name", "ผู้จำหน่าย", "taxId", "0105550123451", "branchType", "weird",
            "contactName", "", "phone", "", "email", "", "creditDays", 0, "note", "", "active", true)))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(BAD_REQUEST, true));
    assertThat(rows(c, "dt_suppliers")).isZero();
  }

  @Test
  void numericOverflowIs400() throws Exception {
    Company c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    long leaf = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    long product = id(mvc.perform(as(owner, post("/products"), Map.of("sku", "P1", "name", "สินค้า", "categoryId", leaf, "unit", "ชิ้น"))
        .header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
    mvc.perform(as(owner, put("/products/" + product + "/stock"), Map.of("delta", 1, "unitCost", 1e20, "note", "")).header("X-Branch-Id", c.hq()))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(BAD_REQUEST, true));
    assertThat(rows(c, "dt_stock_movements")).isZero();
  }

  @Test
  void nulCharacterInTextIs400() throws Exception {
    Company c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "a\u0000b", "active", true)))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(BAD_REQUEST, true));
    assertThat(rows(c, "dt_categories")).isZero();
  }
}
