package com.an22.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * กวาดข้าม tenant ทุก endpoint ที่มี id ใน path (แผนขั้น 13): บริษัท B มีข้อมูลครบทุกชนิด → owner ของบริษัท A เรียกด้วย id ของ B → 404 ทุกตัว
 * และข้อมูลของ B ไม่เปลี่ยนเลย. endpoint ใหม่ที่มี {@code {id}} ต้องเพิ่มในตาราง {@link #ROUTES} (ไม่งั้น test แดง).
 */
class CrossTenantSweepIT extends ApiTest {

  /** {@code METHOD path} → ชนิดของ id (ตาราง {@link #ids}). */
  private static final Map<String, String> ROUTES = new LinkedHashMap<>();

  static {
    for (String kind : List.of("categories", "payment-methods", "pos-terminals", "prices", "products", "promotions", "suppliers", "users")) {
      ROUTES.put("PUT /" + kind + "/{id}", kind);
      ROUTES.put("DELETE /" + kind + "/{id}", kind);
    }
    for (String kind : List.of("branches", "products", "promotions", "suppliers", "transfers", "users", "sales", "shifts")) {
      ROUTES.put("GET /" + kind + "/{id}", kind);
    }
    ROUTES.put("PUT /branches/{id}", "branches");
    ROUTES.put("DELETE /pos/held-bills/{id}", "held-bills");
    for (String sub : List.of("branch-stock", "movements", "serials")) {
      ROUTES.put("GET /products/{id}/" + sub, "products");
    }
    ROUTES.put("POST /products/{id}/serials", "products");
    ROUTES.put("POST /products/{id}/serials/remove", "products");
    ROUTES.put("PUT /products/{id}/stock", "products");
    for (String sub : List.of("credit-notes", "exchanges", "tax-invoice", "tax-invoices")) {
      ROUTES.put("GET /sales/{id}/" + sub, "sales");
    }
    for (String sub : List.of("credit-notes", "exchanges", "tax-invoice", "tax-invoice/reissue", "void")) {
      ROUTES.put("POST /sales/{id}/" + sub, "sales");
    }
    ROUTES.put("PUT /sales/{id}/status", "sales");
    for (String sub : List.of("adjustments", "drawer-opens", "movements", "summary")) {
      ROUTES.put("GET /shifts/{id}/" + sub, "shifts");
    }
    for (String sub : List.of("adjustments", "approve", "cash-movements", "close", "count-check", "drawer-opens", "lock", "unlock")) {
      ROUTES.put("POST /shifts/{id}/" + sub, "shifts");
    }
    ROUTES.put("POST /transfers/{id}/cancel", "transfers");
    ROUTES.put("POST /transfers/{id}/receive", "transfers");
    ROUTES.put("PUT /users/{id}/password", "users");
  }

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

  private final Map<String, String> ids = new HashMap<>();

  private long id(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    assertThat(result.andReturn().getResponse().getStatus()).as(body).isEqualTo(200);
    return JSON.readTree(body).path("id").asLong();
  }

  /** บริษัท B: ทุกชนิดข้อมูลที่มี id ใน path. */
  private void seedB(Company b) throws Exception {
    String owner = login(b.code(), "owner", TestCompanies.PASSWORD);
    String staff = login(b.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", 7, "name", "เครื่อง 7", "posId", "", "branchId", b.hq(), "active", true)));
    ids.put("pos-terminals", "7");
    long cash = id(mvc.perform(as(owner, post("/payment-methods"), Map.of("code", "CASH", "name", "เงินสด", "type", "cash", "active", true))));
    ids.put("payment-methods", Long.toString(cash));
    long category = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    ids.put("categories", Long.toString(category));
    Map<String, Object> supplier = new HashMap<>(Map.of("code", "S1", "name", "ผู้ขาย", "taxId", "0105550123451", "branchType", "head",
        "branchNo", "00000", "email", "", "creditDays", 15));
    ids.put("suppliers", Long.toString(id(mvc.perform(as(owner, post("/suppliers"), supplier)))));
    long mouse = sku(owner, b, "MS", category, false);
    long laptop = sku(owner, b, "NB", category, true);
    ids.put("products", Long.toString(laptop));
    Map<String, Object> price = new HashMap<>(Map.of("productId", mouse, "price", 107, "startDate", "2020-01-01", "note", ""));
    price.put("endDate", null);
    ids.put("prices", Long.toString(id(mvc.perform(as(owner, post("/prices"), price)))));
    Map<String, Object> laptopPrice = new HashMap<>(price);
    laptopPrice.put("productId", laptop);
    laptopPrice.put("price", 25000);
    id(mvc.perform(as(owner, post("/prices"), laptopPrice)));
    Map<String, Object> promo = new HashMap<>(Map.of("code", "P1", "name", "โปร", "type", "item_discount", "startDate", "2099-01-01",
        "scope", Map.of("all", false, "productIds", List.of(mouse), "categoryIds", List.of()), "discount", Map.of("kind", "percent", "value", 10)));
    promo.put("endDate", null);
    ids.put("promotions", Long.toString(id(mvc.perform(as(owner, post("/promotions"), promo)))));
    mvc.perform(at(b, as(owner, put("/products/" + mouse + "/stock"), Map.of("delta", 20, "unitCost", 60, "note", "รับ"))));
    mvc.perform(at(b, as(owner, post("/products/" + laptop + "/serials"), Map.of("serials", List.of("SN-1", "SN-2", "SN-3"), "unitCost", 20000))));
    Map<String, Object> transfer = Map.of("toBranchId", b.bn(), "lines", List.of(Map.of("productId", mouse, "qty", 1, "serials", List.of())),
        "note", "");
    ids.put("transfers", Long.toString(id(mvc.perform(as(owner, post("/transfers"), transfer).header("X-Branch-Id", b.hq())))));
    long shift = id(mvc.perform(at(b, as(staff, post("/shifts/open"), Map.of("openingFloat", 1000)))));
    ids.put("shifts", Long.toString(shift));
    Map<String, Object> laptopItem = new HashMap<>(Map.of("productId", laptop, "factor", 1, "qty", 1, "serial", "SN-1"));
    Map<String, Object> mouseItem = new HashMap<>(Map.of("productId", mouse, "factor", 1, "qty", 2));
    mouseItem.put("serial", null);
    Map<String, Object> sale = new HashMap<>();
    sale.put("items", List.of(mouseItem, laptopItem));
    sale.put("freeSerials", List.of());
    sale.put("payments", List.of(Map.of("methodId", cash, "amount", 25214, "reference", "")));
    sale.put("customer", "");
    sale.put("expectedTotal", 25214);
    long saleId = id(mvc.perform(at(b, as(staff, post("/sales"), sale))));
    ids.put("sales", Long.toString(saleId));
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = :id").param("id", saleId).update());
    Map<String, Object> buyer = Map.of("name", "บริษัท ลูกค้า จำกัด", "taxId", "0105550123451", "branchType", "head", "branchNo", "00000",
        "address", "กรุงเทพ");
    mvc.perform(at(b, as(owner, post("/sales/" + saleId + "/tax-invoice"), Map.of("buyer", buyer))));
    mvc.perform(at(b, as(owner, post("/sales/" + saleId + "/credit-notes"), Map.of("lines", List.of(Map.of("saleLineIndex", 0, "qty", 1,
        "restock", true)), "refunds", List.of(Map.of("methodId", cash, "amount", 107, "reference", "")), "reason", "ชำรุด", "expectedTotal", 107))));
    Map<String, Object> swap = new HashMap<>(Map.of("saleLineIndex", 1, "qty", 1, "newSerial", "SN-2", "restock", true));
    mvc.perform(at(b, as(owner, post("/sales/" + saleId + "/exchanges"), Map.of("lines", List.of(swap), "reason", "เสีย"))));
    Map<String, Object> bill = new HashMap<>();
    bill.put("heldAt", "2026-10-23T03:00:00Z");
    bill.put("customer", "");
    bill.put("items", List.of(Map.of("productId", mouse, "factor", 1, "qty", 1, "serial", "")));
    bill.put("freeSerials", List.of());
    bill.put("buyer", null);
    bill.put("billManual", null);
    bill.put("manualReason", "");
    bill.put("total", 107);
    String held = JSON.readTree(mvc.perform(at(b, as(staff, post("/pos/held-bills"), bill))).andReturn().getResponse().getContentAsString())
        .path("id").asString();
    ids.put("held-bills", held);
    ids.put("branches", Long.toString(b.bn()));
    ids.put("users", Long.toString(b.staff()));
  }

  private long sku(String owner, Company b, String code, long category, boolean serial) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("sku", code, "name", "สินค้า " + code, "categoryId", category, "unit", "ชิ้น",
        "serialControl", serial, "serialPrefix", serial ? "SN" : ""));
    return id(mvc.perform(as(owner, post("/products"), m).header("X-Branch-Id", b.hq())));
  }

  private static MockHttpServletRequestBuilder at(Company c, MockHttpServletRequestBuilder request) {
    return request.header("X-Terminal-Id", 7).header("X-Branch-Id", c.hq());
  }

  /** จำนวนแถว + เวลาแก้ล่าสุดของทุกตารางของบริษัท — ต้องไม่เปลี่ยนเลยระหว่างกวาด. */
  private Map<String, String> fingerprint(long tenant) {
    List<String> tables = sql.platform(jdbc -> jdbc.sql("SELECT DISTINCT table_name FROM information_schema.columns"
        + " WHERE table_schema = 'public' AND column_name = 'tenant_id' ORDER BY table_name").query(String.class).list());
    Map<String, String> out = new LinkedHashMap<>();
    for (String t : tables) {
      boolean updated = sql.platform(jdbc -> jdbc.sql("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = :t"
          + " AND column_name = 'updated_at')").param("t", t).query(Boolean.class).single());
      String stamp = updated ? "max(updated_at)::text" : "max(created_at)::text";
      out.put(t, sql.platform(jdbc -> jdbc.sql("SELECT count(*) || '|' || coalesce(" + stamp + ", '-') FROM " + t + " WHERE tenant_id = :id")
          .param("id", tenant).query(String.class).single()));
    }
    out.put("dt_companies", sql.platform(jdbc -> jdbc.sql("SELECT updated_at::text FROM dt_companies WHERE id = :id").param("id", tenant)
        .query(String.class).single()));
    return out;
  }

  @Test
  void everyRouteWithAnIdAnswers404ForAnotherCompanysIdAndChangesNothing() throws Exception {
    // ตารางครบทุก endpoint ของร้านที่มี {id}
    Set<String> actual = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String pattern : info.getPatternValues()) {
        if (pattern.contains("{") && !pattern.startsWith("/platform/") && !pattern.startsWith("/test-probe")) {
          info.getMethodsCondition().getMethods().forEach(m -> actual.add(m.name() + " " + pattern));
        }
      }
    }
    assertThat(actual).as("endpoint ที่มี {id} ต้องอยู่ใน ROUTES").isEqualTo(new TreeSet<>(ROUTES.keySet()));

    Company b = companies.create();
    seedB(b);
    assertThat(ids.keySet()).containsAll(ROUTES.values());
    Company a = companies.create();
    String owner = login(a.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", 1, "name", "เครื่อง 1", "posId", "", "branchId", a.hq(), "active", true)));
    Map<String, String> before = fingerprint(b.id());

    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, String> route : ROUTES.entrySet()) {
      String[] parts = route.getKey().split(" ");
      String path = parts[1].replace("{id}", ids.get(route.getValue()));
      MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(parts[0]), "/api" + path).contextPath("/api")
          .header("Authorization", bearer(owner)).header("X-Branch-Id", a.hq()).header("X-Terminal-Id", 1).header("X-Real-IP", "198.18.9.9");
      if (!"GET".equals(parts[0]) && !"DELETE".equals(parts[0])) {
        request.contentType("application/json").content("{}");
      }
      var response = mvc.perform(request).andReturn().getResponse();
      if (response.getStatus() != 404) {
        wrong.add(route.getKey() + " → " + response.getStatus() + " " + response.getContentAsString());
      }
    }
    assertThat(wrong).as("ต้อง 404 ทุกตัว").isEmpty();
    assertThat(fingerprint(b.id())).isEqualTo(before);

    // ตัวควบคุม: id เดียวกันใช้ได้จริงกับเจ้าของ (GET → 200) — 404 ข้างบนจึงมาจาก tenant ไม่ใช่ id ผิด
    String ownerB = login(b.code(), "owner", TestCompanies.PASSWORD);
    for (Map.Entry<String, String> route : ROUTES.entrySet()) {
      String[] parts = route.getKey().split(" ");
      if (!"GET".equals(parts[0])) {
        continue;
      }
      String path = parts[1].replace("{id}", ids.get(route.getValue()));
      var response = mvc.perform(lastYear(as(ownerB, get(path)).header("X-Branch-Id", b.hq()))).andReturn().getResponse();
      assertThat(response.getStatus()).as(route.getKey() + " " + response.getContentAsString()).isEqualTo(200);
    }
    // ข้อมูลของ B ที่สร้างผ่าน API ครบทุกชนิดเอกสาร
    for (String table : List.of("dt_tax_invoices", "dt_credit_notes", "dt_exchanges", "dt_held_bills", "dt_transfers", "dt_promotions")) {
      long rows = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", b.id())
          .query(Long.class).single());
      assertThat(rows).as(table).isPositive();
    }
  }
}
