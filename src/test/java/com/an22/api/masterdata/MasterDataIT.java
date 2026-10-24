package com.an22.api.masterdata;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** {@code /categories}, {@code /suppliers}, {@code /payment-methods} — seed DEMO, สิทธิ์, กฎ, ข้าม tenant. */
class MasterDataIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String staff;
  private String demo;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    demo = login("DEMO", "somchai", "1234");
  }

  @Test
  void demoSeedMatchesTheMock() throws Exception {
    mvc.perform(as(demo, get("/categories"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(17))
        .andExpect(jsonPath("$[2].code").value("IT-COM-NB"))
        .andExpect(jsonPath("$[2].parentId").value(2))
        .andExpect(jsonPath("$[2].level").value(3))
        .andExpect(jsonPath("$[2].productCount").value(1));
    mvc.perform(as(demo, get("/suppliers/2")))
        .andExpect(jsonPath("$.code").value("SUP-CPS")).andExpect(jsonPath("$.branchType").value("branch"))
        .andExpect(jsonPath("$.branchNo").value("00001")).andExpect(jsonPath("$.creditDays").value(60));
    mvc.perform(as(demo, get("/payment-methods")))
        .andExpect(jsonPath("$[*].code", Matchers.contains("CASH", "CARD", "QR-PP", "TRANSFER", "TMW", "INST-0")))
        .andExpect(jsonPath("$[0].cashRounding").value("0.25"))
        .andExpect(jsonPath("$[1].minAmount").value(300.0))
        .andExpect(jsonPath("$[1].maxAmount").value((Object) null))
        .andExpect(jsonPath("$[5].installmentMonths", Matchers.contains(3, 6, 10)))
        .andExpect(jsonPath("$[3].closeCheck").value("confirm"));
    // ข้อมูลของ DEMO ไม่เห็นในบริษัทอื่น
    mvc.perform(as(owner, get("/categories"))).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(owner, get("/suppliers/2"))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบผู้จำหน่าย"), true));
  }

  @Test
  void categoryTreeRules() throws Exception {
    mvc.perform(as(staff, post("/categories"), Map.of("code", "A", "name", "a", "active", true)))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ไขหมวดหมู่ได้เฉพาะผู้ดูแลระบบ"), true));
    long l1 = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "L1", "name", " ระดับ 1 ", "active", true)))
        .andExpect(jsonPath("$.level").value(1)).andExpect(jsonPath("$.name").value("ระดับ 1")));
    long l2 = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "L2", "name", "ระดับ 2", "parentId", l1, "active", true))));
    long l3 = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "L3", "name", "ระดับ 3", "parentId", l2, "active", true)))
        .andExpect(jsonPath("$.level").value(3)));
    mvc.perform(as(owner, post("/categories"), Map.of("code", "L4", "name", "x", "parentId", l3, "active", true)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("แบ่งหมวดหมู่ได้ไม่เกิน 3 ระดับ"), true));
    mvc.perform(as(owner, post("/categories"), Map.of("code", "L1", "name", "x", "active", true)))
        .andExpect(content().json(message("รหัส L1 ซ้ำ"), true));
    mvc.perform(as(owner, post("/categories"), Map.of("code", "l-x", "name", "x", "active", true)))
        .andExpect(content().json(message("รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -"), true));
    mvc.perform(as(owner, post("/categories"), Map.of("code", "X9", "name", "x", "parentId", 999_999, "active", true)))
        .andExpect(content().json(message("ไม่พบหมวดหมู่แม่"), true));
    mvc.perform(as(owner, delete("/categories/" + l2))).andExpect(content().json(message("ยังมีหมวดหมู่ย่อย ลบไม่ได้"), true));
    mvc.perform(as(owner, put("/categories/" + l3), Map.of("code", "L3X", "name", "ใหม่", "active", false)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("L3X")).andExpect(jsonPath("$.parentId").value(l2))
        .andExpect(jsonPath("$.active").value(false));
    mvc.perform(as(owner, delete("/categories/" + l3))).andExpect(status().isOk());
    // หมวดของ DEMO ไม่พบในบริษัทนี้
    mvc.perform(as(owner, put("/categories/1"), Map.of("code", "IT", "name", "x", "active", true))).andExpect(status().isNotFound());
  }

  private static Map<String, Object> supplier(String code, String taxId) {
    Map<String, Object> m = new HashMap<>();
    m.put("code", code);
    m.put("name", " ผู้ขาย ");
    m.put("taxId", taxId);
    m.put("branchType", "head");
    m.put("branchNo", "00009");
    m.put("email", "");
    m.put("creditDays", 15);
    return m;
  }

  @Test
  void supplierRules() throws Exception {
    mvc.perform(as(owner, post("/suppliers"), supplier("S1", "0105550123451")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("ผู้ขาย")).andExpect(jsonPath("$.branchNo").value(""))
        .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.productCount").value(0));
    mvc.perform(as(owner, post("/suppliers"), supplier("S2", "0105550123452")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง"), true));
    mvc.perform(as(owner, post("/suppliers"), supplier("S2", "0105550123451")))
        .andExpect(content().json(message("เลขผู้เสียภาษีและสาขานี้มีอยู่แล้ว"), true));
    Map<String, Object> branch = supplier("S2", "0105550123451");
    branch.put("branchType", "branch");
    branch.put("branchNo", "12");
    mvc.perform(as(owner, post("/suppliers"), branch)).andExpect(content().json(message("เลขสาขาต้องเป็นตัวเลข 5 หลัก"), true));
    branch.put("branchNo", "00001");
    long id = id(mvc.perform(as(owner, post("/suppliers"), branch)).andExpect(status().isOk()));
    mvc.perform(as(staff, delete("/suppliers/" + id))).andExpect(status().isForbidden())
        .andExpect(content().json(message("แก้ไขผู้จำหน่ายได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(owner, delete("/suppliers/" + id))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/suppliers/" + id))).andExpect(status().isNotFound());
  }

  private static Map<String, Object> method(String code, String type) {
    Map<String, Object> m = new HashMap<>();
    m.put("code", code);
    m.put("name", "ช่องทาง " + code);
    m.put("type", type);
    m.put("active", true);
    m.put("minAmount", 0);
    m.put("maxAmount", 0);
    m.put("feePercent", 1.5);
    return m;
  }

  @Test
  void paymentMethodsKeepOneActiveCashAndOrder() throws Exception {
    long cash = id(mvc.perform(as(owner, post("/payment-methods"), method("cash", "cash")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("CASH")).andExpect(jsonPath("$.feePercent").value(0.0))
        .andExpect(jsonPath("$.maxAmount").value((Object) null)).andExpect(jsonPath("$.closeCheck").value("count"))
        .andExpect(jsonPath("$.sortOrder").value(1)));
    long card = id(mvc.perform(as(owner, post("/payment-methods"), method("CARD", "card")))
        .andExpect(jsonPath("$.sortOrder").value(2)).andExpect(jsonPath("$.feePercent").value(1.5)));
    Map<String, Object> off = method("CASH", "cash");
    off.put("active", false);
    mvc.perform(as(owner, put("/payment-methods/" + cash), off))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง"), true));
    mvc.perform(as(owner, delete("/payment-methods/" + cash)))
        .andExpect(content().json(message("ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง ลบไม่ได้"), true));
    mvc.perform(as(owner, put("/payment-methods/order"), Map.of("ids", List.of(card))))
        .andExpect(content().json(message("ลำดับไม่ครบหรือมีรายการที่ไม่รู้จัก กรุณาโหลดใหม่"), true));
    mvc.perform(as(owner, put("/payment-methods/order"), Map.of("ids", List.of(card, cash))))
        .andExpect(status().isOk()).andExpect(jsonPath("$[*].code", Matchers.contains("CARD", "CASH")));
    mvc.perform(as(staff, put("/payment-methods/order"), Map.of("ids", List.of(cash, card))))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ไขช่องทางชำระเงินได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(owner, delete("/payment-methods/" + card))).andExpect(status().isOk());
    // ช่องทางของ DEMO ไม่พบในบริษัทนี้
    mvc.perform(as(owner, put("/payment-methods/1"), method("CASH", "cash"))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบช่องทางชำระเงิน"), true));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  /** E2E B3: เดือนผ่อนทศนิยม → ข้อความของ mock; field จำนวนเต็มที่ไม่มีกฎของ mock (creditDays) ทศนิยม → 400 ข้อความกลาง (ไม่ตัดเงียบ ๆ). */
  @Test
  void fractionalIntegersAreRejected() throws Exception {
    Map<String, Object> installment = method("INST", "installment");
    installment.put("installmentMonths", List.of(2.5, 10));
    mvc.perform(as(owner, post("/payment-methods"), installment))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("จำนวนเดือนผ่อนต้องเป็นจำนวนเต็ม 2 - 60"), true));
    Map<String, Object> supplier = supplier("S9", "0105550123451");
    supplier.put("creditDays", 2.5);
    mvc.perform(as(owner, post("/suppliers"), supplier))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ข้อมูลที่ส่งมาไม่ถูกต้อง"), true));
  }
}
