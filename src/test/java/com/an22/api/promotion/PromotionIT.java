package com.an22.api.promotion;

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
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** {@code /promotions} — seed, normalize, ล็อกหลังเริ่ม, จำกัดสาขา (D2 / E6), ลบ SKU / หมวดที่โปรอ้าง, audit, ข้าม tenant. */
class PromotionIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String boss;
  private String staff;
  private long leaf;
  private long product;
  private final LocalDate today = LocalDate.now(ClockConfig.BANGKOK);

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    leaf = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    product = id(mvc.perform(as(owner, post("/products"), Map.of("sku", "P1", "name", "สินค้า", "categoryId", leaf, "unit", "ชิ้น"))
        .header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  /** โปรลดราคา SKU ของบริษัท เริ่มในอีก {@code startIn} วัน. */
  private Map<String, Object> promo(String code, int startIn) {
    Map<String, Object> m = new HashMap<>();
    m.put("code", code);
    m.put("name", "โปร " + code);
    m.put("type", "item_discount");
    m.put("startDate", today.plusDays(startIn).toString());
    m.put("endDate", null);
    m.put("scope", Map.of("all", false, "productIds", List.of(product), "categoryIds", List.of()));
    m.put("discount", Map.of("kind", "percent", "value", 10));
    return m;
  }

  private ResultActions create(String token, Map<String, Object> body) throws Exception {
    return mvc.perform(as(token, post("/promotions"), body));
  }

  @Test
  void demoSeedHasPromotions() throws Exception {
    String admin = login("DEMO", "admin", "admin");
    mvc.perform(as(admin, get("/promotions")))
        .andExpect(jsonPath("$[*].code",
            Matchers.contains("PRO-MSKB10", "PRO-BILL5K", "PRO-NB-GIFT", "PRO-USB-2F1", "PRO-1111", "PRO-SUMMER")));
    mvc.perform(as(admin, get("/promotions/3")))
        .andExpect(jsonPath("$.freeGoods.items[*].productId", Matchers.contains(2, 9)))
        .andExpect(jsonPath("$.discount").value((Object) null)).andExpect(jsonPath("$.branchIds.length()").value(0))
        .andExpect(jsonPath("$.endDate").value((Object) null));
    mvc.perform(as(admin, get("/promotions/5"))).andExpect(jsonPath("$.discount.maxDiscount").value(300.0));
  }

  @Test
  void createNormalizesAndValidates() throws Exception {
    Map<String, Object> body = promo(" pro-a ", 10);
    body.put("name", "  ลด 10%  ");
    body.put("endDate", "");
    long id = id(create(owner, body).andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("PRO-A")).andExpect(jsonPath("$.name").value("ลด 10%"))
        .andExpect(jsonPath("$.endDate").value((Object) null)).andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.priority").value(0.0)).andExpect(jsonPath("$.minQty").value(1.0))
        .andExpect(jsonPath("$.discount.maxDiscount").value((Object) null)).andExpect(jsonPath("$.freeGoods").value((Object) null)));
    mvc.perform(as(staff, get("/promotions/" + id))).andExpect(jsonPath("$.scope.productIds[0]").value((int) product));
    create(owner, promo("PRO-A", 10)).andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสโปร PRO-A ซ้ำ"), true));

    Map<String, Object> bill = promo("BILL", 10);
    bill.put("type", "bill_discount");
    bill.put("minQty", 0);
    bill.put("minAmount", 5000);
    create(owner, bill).andExpect(status().isOk()).andExpect(jsonPath("$.scope.all").value(true))
        .andExpect(jsonPath("$.scope.productIds.length()").value(0));

    Map<String, Object> free = promo("FREE", 10);
    free.put("type", "free_goods");
    free.put("discount", null);
    free.put("freeGoods", Map.of("items", List.of(Map.of("productId", product, "qty", 1)), "repeat", true));
    create(owner, free).andExpect(status().isOk()).andExpect(jsonPath("$.freeGoods.maxSets").value((Object) null))
        .andExpect(jsonPath("$.freeGoods.items[0].qty").value(1.0));

    Map<String, Object> bad = promo("BAD", 10);
    bad.put("priority", 1.5);
    create(owner, bad).andExpect(content().json(message("ลำดับความสำคัญต้องเป็นจำนวนเต็ม 0 - 999"), true));
    bad = promo("BAD", 10);
    bad.put("type", "mystery");
    create(owner, bad).andExpect(status().isBadRequest()).andExpect(content().json(message("ข้อมูลที่ส่งมาไม่ถูกต้อง"), true));
    create(staff, promo("STAFF", 10)).andExpect(status().isForbidden())
        .andExpect(content().json(message("แก้ไขโปรโมชั่นได้เฉพาะผู้ดูแลระบบ"), true));
  }

  @Test
  void startedPromotionsAreLockedAndCannotBeDeleted() throws Exception {
    Map<String, Object> body = promo("NOW", 0);
    body.put("endDate", today.plusDays(30).toString());
    long id = id(create(owner, body).andExpect(status().isOk()));
    body.put("name", "ชื่อใหม่");
    mvc.perform(as(owner, put("/promotions/" + id), body)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("โปรที่เริ่มแล้วแก้ได้เฉพาะวันสิ้นสุด (เลื่อนให้เร็วขึ้น) การเปิด/ปิดใช้งาน และหมายเหตุ"), true));
    body.put("name", "โปร NOW");
    body.put("enabled", false);
    body.put("note", "หยุดชั่วคราว");
    body.put("endDate", today.plusDays(7).toString());
    mvc.perform(as(owner, put("/promotions/" + id), body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.note").value("หยุดชั่วคราว"));
    body.put("endDate", today.plusDays(8).toString());
    mvc.perform(as(owner, put("/promotions/" + id), body))
        .andExpect(content().json(message("โปรที่เริ่มแล้วเลื่อนวันสิ้นสุดให้ช้าลงไม่ได้ (สร้างโปรใหม่แทน)"), true));
    mvc.perform(as(owner, delete("/promotions/" + id))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("โปรที่เริ่มแล้วลบไม่ได้ (ปิดใช้งานแทน)"), true));

    long later = id(create(owner, promo("LATER", 5)).andExpect(status().isOk()));
    mvc.perform(as(owner, delete("/promotions/" + later))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/promotions/" + later))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบโปรโมชั่น"), true));
    assertThat(companies.audit(c, "promotion_change")).containsExactly(
        "promotion_change|NOW โปร NOW|เพิ่มโปรโมชั่น",
        "promotion_change|NOW โปร NOW|แก้โปรโมชั่น (ปิดใช้งาน)",
        "promotion_change|LATER โปร LATER|เพิ่มโปรโมชั่น",
        "promotion_change|LATER โปร LATER|ลบโปรโมชั่น");
  }

  @Test
  void onlyCompanyAdminsLimitPromotionsToBranches() throws Exception {
    Map<String, Object> limited = promo("HQ-ONLY", 10);
    limited.put("branchIds", List.of(c.hq()));
    String companyOnly = message("โปรที่จำกัดสาขาสร้าง / แก้ได้เฉพาะผู้ดูแลระบบระดับบริษัท");
    create(boss, limited).andExpect(status().isForbidden()).andExpect(content().json(companyOnly, true));
    long id = id(create(owner, limited).andExpect(status().isOk()).andExpect(jsonPath("$.branchIds[0]").value((int) c.hq())));
    limited.put("branchIds", List.of());
    mvc.perform(as(boss, put("/promotions/" + id), limited)).andExpect(status().isForbidden()).andExpect(content().json(companyOnly, true));
    mvc.perform(as(boss, delete("/promotions/" + id))).andExpect(status().isForbidden());
    Map<String, Object> closed = promo("OLD-BRANCH", 10);
    closed.put("branchIds", List.of(c.old()));
    create(owner, closed).andExpect(content().json(message("สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว"), true));
    // ไม่จำกัดสาขา: admin สาขาทำได้
    create(boss, promo("ALL", 10)).andExpect(status().isOk());
  }

  @Test
  void skusAndCategoriesInUnexpiredPromotionsCannotBeDeleted() throws Exception {
    create(owner, promo("USE-SKU", 10)).andExpect(status().isOk());
    Map<String, Object> expired = promo("EXPIRED", -10);
    expired.put("endDate", today.minusDays(1).toString());
    create(owner, expired).andExpect(status().isOk());
    mvc.perform(as(owner, delete("/products/" + product))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("SKU นี้อยู่ในโปรโมชั่น USE-SKU ลบไม่ได้"), true));

    long empty = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-2", "name", "ว่าง", "parentId", parentOf(), "active", true))));
    Map<String, Object> byCategory = promo("USE-CAT", 10);
    byCategory.put("scope", Map.of("all", false, "productIds", List.of(), "categoryIds", List.of(empty)));
    create(owner, byCategory).andExpect(status().isOk());
    Map<String, Object> gift = promo("GIFT", 10);
    gift.put("type", "free_goods");
    gift.put("discount", null);
    gift.put("scope", Map.of("all", true, "productIds", List.of(), "categoryIds", List.of()));
    gift.put("freeGoods", Map.of("items", List.of(Map.of("productId", product, "qty", 1)), "repeat", false, "maxSets", 2));
    create(owner, gift).andExpect(status().isOk());
    mvc.perform(as(owner, delete("/categories/" + empty))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("หมวดหมู่นี้อยู่ในโปรโมชั่น USE-CAT ลบไม่ได้"), true));
    mvc.perform(as(owner, delete("/products/" + product)))
        .andExpect(content().json(message("SKU นี้อยู่ในโปรโมชั่น USE-SKU, GIFT ลบไม่ได้"), true));
  }

  private long parentOf() throws Exception {
    String body = mvc.perform(as(owner, get("/categories"))).andReturn().getResponse().getContentAsString();
    return JSON.readTree(body).get(0).path("id").asLong();
  }

  @Test
  void anotherCompanysPromotionsAreNotFound() throws Exception {
    mvc.perform(as(owner, get("/promotions"))).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(owner, get("/promotions/1"))).andExpect(status().isNotFound());
    mvc.perform(as(owner, put("/promotions/1"), promo("X", 10))).andExpect(status().isNotFound());
    mvc.perform(as(owner, delete("/promotions/1"))).andExpect(status().isNotFound());
    Map<String, Object> foreign = promo("FOREIGN", 10);
    foreign.put("scope", Map.of("all", false, "productIds", List.of(1), "categoryIds", List.of()));
    create(owner, foreign).andExpect(content().json(message("ไม่พบ SKU #1"), true));
    // รหัสซ้ำตรวจในบริษัทเดียวกันเท่านั้น
    create(owner, promo("PRO-1111", 10)).andExpect(status().isOk());
  }

  /** E2E B4: โปรส่วนลดที่ไม่มี discount (null / ไม่ส่ง / PUT แทนทั้งตัวที่ไม่ส่ง) → 400 ไม่เติม 10% เงียบ ๆ. */
  @Test
  void discountPromotionWithoutDiscountIsRejected() throws Exception {
    Map<String, Object> nullDiscount = promo("PRO-N", 0);
    nullDiscount.put("discount", null);
    create(owner, nullDiscount).andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากำหนดส่วนลด"), true));
    Map<String, Object> bill = promo("PRO-B", 0);
    bill.put("type", "bill_discount");
    bill.remove("discount");
    create(owner, bill).andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากำหนดส่วนลด"), true));

    // ยังไม่เริ่ม (โปรที่เริ่มแล้วแก้ส่วนลดไม่ได้อยู่แล้ว)
    Map<String, Object> ok = promo("PRO-OK", 5);
    ok.put("discount", Map.of("kind", "amount", "value", 30));
    long id = id(create(owner, ok).andExpect(status().isOk()));
    Map<String, Object> noDiscount = promo("PRO-OK", 5);
    noDiscount.remove("discount");
    mvc.perform(as(owner, put("/promotions/" + id), noDiscount))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากำหนดส่วนลด"), true));
    mvc.perform(as(owner, get("/promotions/" + id)))
        .andExpect(jsonPath("$.discount.kind").value("amount")).andExpect(jsonPath("$.discount.value").value(30.0));
  }
}
