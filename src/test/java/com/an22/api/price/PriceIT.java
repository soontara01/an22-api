package com.an22.api.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** {@code /prices} — seed, ช่วงทับ, currentPrice ของ SKU, สิทธิ์, audit, ลบ SKU พร้อมราคา, ข้าม tenant. */
class PriceIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String staff;
  private long product;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    long leaf = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    product = id(mvc.perform(as(owner, post("/products"), Map.of("sku", "P1", "name", "สินค้า", "categoryId", leaf, "unit", "ชิ้น"))
        .header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  private static Map<String, Object> price(long product, Object price, String start, String end) {
    Map<String, Object> m = new HashMap<>();
    m.put("productId", product);
    m.put("price", price);
    m.put("startDate", start);
    m.put("endDate", end);
    m.put("note", "");
    return m;
  }

  @Test
  void demoSeedHasPricePeriods() throws Exception {
    String admin = login("DEMO", "admin", "admin");
    mvc.perform(as(admin, get("/prices").param("productId", "1")))
        .andExpect(jsonPath("$[*].price", Matchers.contains(26900.0, 24900.0, 23900.0)))
        .andExpect(jsonPath("$[2].endDate").value((Object) null)).andExpect(jsonPath("$[0].note").value("ราคาเปิดตัว"));
    mvc.perform(as(admin, get("/prices"))).andExpect(jsonPath("$.length()").value(12));
    mvc.perform(as(admin, get("/prices").param("productId", "x"))).andExpect(jsonPath("$.length()").value(12));
  }

  @Test
  void periodsNeverOverlapAndSetTheCurrentPrice() throws Exception {
    String today = LocalDate.now(ClockConfig.BANGKOK).toString();
    long first = id(mvc.perform(as(owner, post("/prices"), price(product, 1234.5, "2020-01-01", null)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value((Object) null)));
    mvc.perform(as(owner, get("/products/" + product)).header("X-Branch-Id", c.hq())).andExpect(jsonPath("$.currentPrice").value(1234.5));
    mvc.perform(as(owner, post("/prices"), price(product, 100, "2030-01-01", "")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("ช่วงวันที่ทับกับราคา ฿1,234.5 (1 ม.ค. 2563 – ไม่กำหนด)"), true));
    // ปิดช่วงแรกเมื่อวาน แล้วเพิ่มช่วงใหม่ตั้งแต่วันนี้
    String yesterday = LocalDate.parse(today).minusDays(1).toString();
    mvc.perform(as(owner, put("/prices/" + first), price(product, 1234.5, "2020-01-01", yesterday))).andExpect(status().isOk())
        .andExpect(jsonPath("$.endDate").value(yesterday));
    mvc.perform(as(owner, get("/products/" + product)).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.currentPrice").value((Object) null));
    long second = id(mvc.perform(as(owner, post("/prices"), price(product, 990, today, null))).andExpect(status().isOk()));
    mvc.perform(as(owner, get("/products")).header("X-Branch-Id", c.hq())).andExpect(jsonPath("$[0].currentPrice").value(990.0));
    mvc.perform(as(owner, get("/prices").param("productId", Long.toString(product))))
        .andExpect(jsonPath("$[*].id", Matchers.contains((int) first, (int) second)));

    mvc.perform(as(owner, post("/prices"), price(product, -1, "2040-01-01", null))).andExpect(content().json(message("ราคาต้องไม่ติดลบ"), true));
    mvc.perform(as(owner, post("/prices"), price(product, 1, "", null))).andExpect(content().json(message("กรุณาระบุวันที่เริ่ม"), true));
    mvc.perform(as(owner, post("/prices"), price(product, 1, "2040-13-01", null)))
        .andExpect(content().json(message("รูปแบบวันที่ไม่ถูกต้อง"), true));
    mvc.perform(as(owner, post("/prices"), price(product, 1, "2040-02-01", "2040-01-31")))
        .andExpect(content().json(message("วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม"), true));
    mvc.perform(as(owner, put("/prices/999999"), price(product, 1, "2040-01-01", null)))
        .andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบช่วงราคา"), true));

    mvc.perform(as(owner, delete("/prices/" + second))).andExpect(status().isOk());
    assertThat(companies.audit(c, "price_change")).containsExactly(
        "price_change|P1 ฿1234.5|เพิ่มช่วงราคา 2020-01-01 – ไม่มีกำหนด",
        "price_change|P1 ฿1234.5|แก้ช่วงราคา 2020-01-01 – " + yesterday,
        "price_change|P1 ฿990|เพิ่มช่วงราคา " + today + " – ไม่มีกำหนด",
        "price_change|P1 ฿990 ตั้งแต่ " + today + "|ลบช่วงราคา");
  }

  @Test
  void onlyAdminsWriteAndDeletingTheSkuDropsItsPrices() throws Exception {
    mvc.perform(as(staff, post("/prices"), price(product, 1, "2040-01-01", null)))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ไขราคาขายได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(staff, get("/prices"))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/prices"), price(product, 1, "2040-01-01", null))).andExpect(status().isOk());
    mvc.perform(as(owner, delete("/products/" + product))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/prices"))).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void anotherCompanysPricesAreNotFound() throws Exception {
    mvc.perform(as(owner, get("/prices"))).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(owner, put("/prices/1"), price(product, 1, "2040-01-01", null))).andExpect(status().isNotFound());
    mvc.perform(as(owner, delete("/prices/1"))).andExpect(status().isNotFound());
    mvc.perform(as(owner, post("/prices"), price(1, 1, "2040-01-01", null)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่พบ SKU"), true));
    // ราคาของ DEMO ยังอยู่ครบ
    mvc.perform(as(login("DEMO", "admin", "admin"), get("/prices"))).andExpect(jsonPath("$.length()").value(12));
  }
}
