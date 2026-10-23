package com.an22.api.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code /pos/held-bills} (E48) — ต่อสาขา + เครื่อง, เข้ารหัสใน DB, ข้าม tenant. */
class HeldBillIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  private Company c;
  private String staff;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    for (int id : new int[] {1, 2}) {
      mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", id, "name", "เครื่อง " + id, "posId", "", "branchId", c.hq(),
          "active", true))).andExpect(status().isOk());
    }
  }

  private MockHttpServletRequestBuilder at(int terminal, MockHttpServletRequestBuilder request) {
    return as(staff, request.header("X-Terminal-Id", terminal).header("X-Branch-Id", c.hq()));
  }

  private static Map<String, Object> bill(String customer) {
    Map<String, Object> m = new HashMap<>();
    m.put("heldAt", "2026-10-23T03:00:00Z");
    m.put("customer", customer);
    m.put("items", List.of(Map.of("productId", 1, "factor", 1, "qty", 2, "serial", "")));
    m.put("freeSerials", List.of());
    m.put("buyer", Map.of("name", "คุณลูกค้า", "taxId", "1234567890123", "address", "กรุงเทพ"));
    m.put("billManual", null);
    m.put("manualReason", "");
    m.put("total", 120.5);
    return m;
  }

  @Test
  void holdsPerTerminalEncryptedAndRemovesOnResume() throws Exception {
    String body = mvc.perform(at(1, post("/pos/held-bills")).contentType("application/json")
            .content(JSON.writeValueAsString(bill("นายลับเฉพาะ"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.customer").value("นายลับเฉพาะ"))
        .andExpect(jsonPath("$.buyer.taxId").value("1234567890123")).andExpect(jsonPath("$.heldAt").value("2026-10-23T03:00:00Z"))
        .andReturn().getResponse().getContentAsString();
    String id = JSON.readTree(body).path("id").asString();
    mvc.perform(at(1, get("/pos/held-bills"))).andExpect(jsonPath("$[*].id", Matchers.contains(id)))
        .andExpect(jsonPath("$[0].items[0].qty").value(2.0));
    mvc.perform(at(2, get("/pos/held-bills"))).andExpect(jsonPath("$.length()").value(0));
    // ข้อมูลผู้ซื้อไม่อยู่ใน DB แบบอ่านได้
    byte[] stored = sql.platform(jdbc -> jdbc.sql("SELECT payload_enc FROM dt_held_bills WHERE id = :id")
        .param("id", Long.parseLong(id)).query(byte[].class).single());
    assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("1234567890123").doesNotContain("ลับเฉพาะ");

    mvc.perform(at(2, delete("/pos/held-bills/" + id))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบบิลที่พักไว้"), true));
    mvc.perform(at(1, delete("/pos/held-bills/" + id))).andExpect(status().isOk());
    mvc.perform(at(1, get("/pos/held-bills"))).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(at(1, delete("/pos/held-bills/abc"))).andExpect(status().isNotFound());
  }

  @Test
  void needsItemsABranchAndARegisteredTerminal() throws Exception {
    Map<String, Object> empty = bill("x");
    empty.put("items", List.of());
    mvc.perform(at(1, post("/pos/held-bills")).contentType("application/json").content(JSON.writeValueAsString(empty)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่มีรายการในบิลที่จะพัก"), true));
    mvc.perform(as(staff, get("/pos/held-bills")).header("X-Terminal-Id", 1)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"), true));
    mvc.perform(at(9, get("/pos/held-bills"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ไม่พบเครื่อง 9 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่"), true));
  }

  @Test
  void anotherCompanysHeldBillIsNotFound() throws Exception {
    String body = mvc.perform(at(1, post("/pos/held-bills")).contentType("application/json")
            .content(JSON.writeValueAsString(bill("ก"))))
        .andReturn().getResponse().getContentAsString();
    String id = JSON.readTree(body).path("id").asString();
    Company other = companies.create();
    String ownerB = login(other.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(ownerB, post("/pos-terminals"), Map.of("id", 1, "name", "เครื่อง 1", "posId", "", "branchId", other.hq(),
        "active", true))).andExpect(status().isOk());
    mvc.perform(as(ownerB, get("/pos/held-bills")).header("X-Terminal-Id", 1).header("X-Branch-Id", other.hq()))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(ownerB, delete("/pos/held-bills/" + id)).header("X-Terminal-Id", 1).header("X-Branch-Id", other.hq()))
        .andExpect(status().isNotFound());
  }
}
