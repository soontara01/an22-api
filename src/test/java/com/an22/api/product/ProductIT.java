package com.an22.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** {@code /products} — seed, สต็อกต่อสาขา (D1), ทุนเฉลี่ย, ซ่อนทุน (E43 / E44), serial (E16), สิทธิ์, ข้าม tenant, พร้อมกัน. */
class ProductIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String staff;
  private long category;
  private long supplier;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    category = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    supplier = id(mvc.perform(as(owner, post("/suppliers"),
        Map.of("code", "S1", "name", "ผู้ขาย", "taxId", "0105550123451", "branchType", "head"))));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  private Map<String, Object> sku(String code, boolean serial) {
    Map<String, Object> m = new HashMap<>();
    m.put("sku", code);
    m.put("name", "สินค้า " + code);
    m.put("shortName", code);
    m.put("categoryId", category);
    m.put("unit", "ชิ้น");
    m.put("barcode", "BC-" + code);
    m.put("cost", 100);
    m.put("minStock", 2);
    m.put("maxStock", 10);
    m.put("serialControl", serial);
    m.put("serialPrefix", serial ? "SN" : "");
    m.put("suppliers", List.of(Map.of("supplierId", supplier, "supplierSku", "X", "cost", 95, "leadTimeDays", 3, "moq", 1, "isMain", true)));
    return m;
  }

  private long create(String code, boolean serial) throws Exception {
    return id(mvc.perform(as(owner, post("/products"), sku(code, serial)).header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private ResultActions stock(String token, long branch, long product, Object delta, Object cost) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("delta", delta);
    body.put("unitCost", cost);
    body.put("note", "ทดสอบ");
    return mvc.perform(as(token, put("/products/" + product + "/stock"), body).header("X-Branch-Id", branch));
  }

  @Test
  void demoSeedHasStockPerBranch() throws Exception {
    String admin = login("DEMO", "admin", "admin");
    mvc.perform(as(admin, get("/products/1")).header("X-Branch-Id", 1))
        .andExpect(jsonPath("$.stock").value(12)).andExpect(jsonPath("$.avgCost").value(19900.0))
        .andExpect(jsonPath("$.categoryPath").value("ไอที > คอมพิวเตอร์ > โน้ตบุ๊ก"))
        .andExpect(jsonPath("$.suppliers[0].isMain").value(true));
    mvc.perform(as(admin, get("/products/1")).header("X-Branch-Id", 2))
        .andExpect(jsonPath("$.stock").value(2)).andExpect(jsonPath("$.avgCost").value(20500.0));
    mvc.perform(as(admin, get("/products/2/branch-stock")))
        .andExpect(jsonPath("$[*].stock", Matchers.contains(3, 8)));
    mvc.perform(as(admin, get("/products/1/serials")).header("X-Branch-Id", 2))
        .andExpect(jsonPath("$[*].serial", Matchers.contains("NB-100000013", "NB-100000014")));
    mvc.perform(as(admin, get("/products/5/movements")).header("X-Branch-Id", 1))
        .andExpect(jsonPath("$.opening.qty").value(25)).andExpect(jsonPath("$.opening.value").value(21250.0))
        .andExpect(jsonPath("$.openingMovements[0].serials.length()").value(25));
    mvc.perform(as(admin, get("/categories"))).andExpect(jsonPath("$[2].productCount").value(1));
    // ราคาวันนี้ (SEED_PRICES: เมาส์ ฿590 ตั้งแต่ 2026-01-01 ไม่กำหนดวันสิ้นสุด)
    mvc.perform(as(admin, get("/products/2")).header("X-Branch-Id", 1)).andExpect(jsonPath("$.currentPrice").value(590.0));
  }

  @Test
  void costIsHiddenFromStaffUnlessTheCompanyAllowsIt() throws Exception {
    long p = create("C1", false);
    stock(owner, c.hq(), p, 5, 120).andExpect(status().isOk());
    mvc.perform(as(staff, get("/products/" + p)).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.cost").value((Object) null)).andExpect(jsonPath("$.avgCost").value((Object) null))
        .andExpect(jsonPath("$.suppliers[0].cost").value((Object) null)).andExpect(jsonPath("$.stock").value(5));
    mvc.perform(as(staff, get("/products/" + p + "/movements")).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.closing.value").value((Object) null)).andExpect(jsonPath("$.closing.qty").value(5))
        .andExpect(jsonPath("$.movements[0].totalCost").value((Object) null));
    mvc.perform(as(staff, get("/products/" + p + "/branch-stock"))).andExpect(jsonPath("$[0].avgCost").value((Object) null));
    // E44: แก้ SKU โดยคนที่เห็นทุนไม่ได้ → ทุนเดิมคงอยู่
    Map<String, Object> edit = sku("C1", false);
    edit.put("cost", 1);
    edit.put("name", "ชื่อใหม่");
    mvc.perform(as(staff, put("/products/" + p), edit).header("X-Branch-Id", c.hq())).andExpect(status().isOk());
    mvc.perform(as(owner, get("/products/" + p)).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.cost").value(100.0)).andExpect(jsonPath("$.suppliers[0].cost").value(95.0))
        .andExpect(jsonPath("$.name").value("ชื่อใหม่"));
    // SKU ใหม่จากคนที่เห็นทุนไม่ได้ = ทุน 0
    long fresh = id(mvc.perform(as(staff, post("/products"), sku("C2", false)).header("X-Branch-Id", c.hq())));
    mvc.perform(as(owner, get("/products/" + fresh))).andExpect(jsonPath("$.cost").value(0.0));
  }

  @Test
  void skuRules() throws Exception {
    mvc.perform(as(owner, post("/products"), sku("R1", false)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"), true));
    create("R1", false);
    mvc.perform(as(owner, post("/products"), sku("r1", false)).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("รหัส SKU r1 ซ้ำ"), true));
    Map<String, Object> dupBarcode = sku("R2", false);
    dupBarcode.put("barcode", "BC-R1");
    mvc.perform(as(owner, post("/products"), dupBarcode).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("บาร์โค้ดซ้ำ: BC-R1"), true));
    Map<String, Object> serialPack = sku("R3", true);
    serialPack.put("packUnits", List.of(Map.of("unit", "กล่อง", "factor", 10, "barcode", "")));
    mvc.perform(as(owner, post("/products"), serialPack).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("สินค้าคุม Serial มีได้หน่วยเดียว"), true));
    Map<String, Object> noMain = sku("R4", false);
    noMain.put("suppliers", List.of(Map.of("supplierId", supplier, "cost", 1, "leadTimeDays", 0, "moq", 0, "isMain", false)));
    mvc.perform(as(owner, post("/products"), noMain).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("ต้องเลือกผู้จำหน่ายหลัก 1 ราย"), true));
    Map<String, Object> parent = sku("R5", false);
    parent.put("categoryId", category - 1);
    mvc.perform(as(owner, post("/products"), parent).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("ต้องเลือกหมวดหมู่ย่อยสุดที่เปิดใช้งาน"), true));
    // หมวดที่มี SKU: ลบ / แตกหมวดย่อยไม่ได้; ผู้จำหน่ายที่ผูก SKU ลบไม่ได้
    mvc.perform(as(owner, delete("/categories/" + category))).andExpect(content().json(message("ยังมี SKU 1 รายการในหมวดนี้ ลบไม่ได้"), true));
    mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1-X", "name", "x", "parentId", category, "active", true)))
        .andExpect(content().json(message("ย่อย มี SKU อยู่ 1 รายการ แบ่งหมวดย่อยไม่ได้"), true));
    mvc.perform(as(owner, delete("/suppliers/" + supplier)))
        .andExpect(content().json(message("ยังผูกกับ SKU 1 รายการ ลบไม่ได้ (ปิดใช้งานแทน)"), true));
  }

  @Test
  void movingAverageIssueAndAudit() throws Exception {
    long p = create("M1", false);
    stock(owner, c.hq(), p, 3, 350).andExpect(jsonPath("$.avgCost").value(350.0));
    stock(owner, c.hq(), p, 20, 300).andExpect(jsonPath("$.stock").value(23)).andExpect(jsonPath("$.avgCost").value(306.5217));
    stock(staff, c.hq(), p, -1, null).andExpect(status().isForbidden())
        .andExpect(content().json(message("ตัดสต็อกออกได้เฉพาะผู้ดูแลระบบ"), true));
    stock(owner, c.hq(), p, -24, null).andExpect(status().isBadRequest()).andExpect(content().json(message("สต็อกคงเหลือไม่พอ"), true));
    stock(owner, c.hq(), p, 1.5, 1).andExpect(content().json(message("จำนวนไม่ถูกต้อง"), true));
    stock(owner, c.hq(), p, -20, null).andExpect(jsonPath("$.stock").value(3)).andExpect(jsonPath("$.avgCost").value(306.5217));
    mvc.perform(as(owner, get("/products/" + p + "/movements")).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.movements[*].type", Matchers.contains("receive", "receive", "issue")))
        .andExpect(jsonPath("$.movements[2].unitCost").value(306.52))
        .andExpect(jsonPath("$.movements[2].totalCost").value(-6130.43))
        .andExpect(jsonPath("$.closing.value").value(919.57))
        .andExpect(jsonPath("$.totals.inQty").value(23));
    // สาขาอื่นของ SKU เดียวกัน = สต็อกแยก (D1)
    mvc.perform(as(owner, get("/products/" + p)).header("X-Branch-Id", c.bn())).andExpect(jsonPath("$.stock").value(0));
    assertThat(companies.audit(c, "stock_out")).containsExactly("stock_out|M1|-20 — ทดสอบ");
    // บัตรสต็อกของสาขาที่ไม่มีสิทธิ์
    mvc.perform(as(staff, get("/products/" + p + "/movements").param("branchId", Long.toString(c.bn()))).header("X-Branch-Id", c.hq()))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ไม่มีสิทธิ์ดูบัตรสต็อกของสาขานี้"), true));
    // ลบไม่ได้เมื่อยังมีของ
    mvc.perform(as(owner, delete("/products/" + p)))
        .andExpect(content().json(message("ยังมีสต็อกคงเหลือ (สาขา HQ) ลบไม่ได้"), true));
  }

  @Test
  void serialsReceiveRemoveAndReturn() throws Exception {
    long p = create("SN1", true);
    Map<String, Object> receive = new HashMap<>(Map.of("serials", List.of(" sn-1 ", "SN-2"), "unitCost", 1000, "note", ""));
    mvc.perform(as(owner, post("/products/" + p + "/serials"), receive).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.product.stock").value(2))
        .andExpect(jsonPath("$.received[*].serial", Matchers.contains("SN-1", "SN-2")));
    mvc.perform(as(owner, post("/products/" + p + "/serials"), Map.of("serials", List.of("SN-1", "XX-3", "SN-4", "SN-4"), "unitCost", 1))
            .header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("รับเข้าไม่ได้ — SN-1: มีอยู่ในคลังแล้ว, XX-3: ต้องขึ้นต้นด้วย SN, SN-4: ซ้ำในรายการ"), true));
    mvc.perform(as(owner, post("/products/" + p + "/serials"), Map.of("serials", List.of("SN-1"), "unitCost", 1))
            .header("X-Branch-Id", c.bn()))
        .andExpect(content().json(message("รับเข้าไม่ได้ — SN-1: อยู่ในคลังสาขา HQ"), true));
    String list = mvc.perform(as(owner, get("/products/" + p + "/serials")).header("X-Branch-Id", c.hq()))
        .andReturn().getResponse().getContentAsString();
    long sn1 = JSON.readTree(list).get(0).path("id").asLong();
    mvc.perform(as(owner, post("/products/" + p + "/serials/remove"), Map.of("ids", List.of(sn1), "status", "damaged", "note", "จอแตก"))
            .header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(1));
    assertThat(companies.audit(c, "serial_remove")).containsExactly("serial_remove|SN1|SN-1 → damaged — จอแตก");
    // รับคืนที่สาขาอื่น: ใช้แถวเดิม ย้ายสาขา + แจ้ง fromOtherBranch (E32)
    mvc.perform(as(owner, post("/products/" + p + "/serials"), Map.of("serials", List.of("SN-1"), "unitCost", 800))
            .header("X-Branch-Id", c.bn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received[0].id").value(sn1)).andExpect(jsonPath("$.received[0].note").value("รับคืนเข้าคลัง"))
        .andExpect(jsonPath("$.fromOtherBranch[0].branchCode").value("HQ"))
        .andExpect(jsonPath("$.product.avgCost").value(800.0));
    // เปลี่ยนการคุม serial เมื่อยังมีของไม่ได้
    Map<String, Object> flip = sku("SN1", false);
    mvc.perform(as(owner, put("/products/" + p), flip).header("X-Branch-Id", c.hq()))
        .andExpect(content().json(message("ยังมีสต็อกคงเหลือ (สาขา HQ, BN) เปลี่ยนการคุม Serial ไม่ได้"), true));
  }

  @Test
  void anotherCompanysSkuIsNotFound() throws Exception {
    mvc.perform(as(owner, get("/products/1")).header("X-Branch-Id", c.hq())).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบ SKU"), true));
    stock(owner, c.hq(), 2, 1, 1).andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบสินค้า"), true));
    mvc.perform(as(owner, delete("/products/1"))).andExpect(status().isNotFound());
    mvc.perform(as(owner, get("/products"))).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void concurrentIssuesNeverOversellAndSerialsSellOnce() throws Exception {
    long p = create("CC1", false);
    stock(owner, c.hq(), p, 10, 50).andExpect(status().isOk());
    List<Callable<Integer>> issues = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      issues.add(() -> stock(owner, c.hq(), p, -2, null).andReturn().getResponse().getStatus());
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(issues)) {
        statuses.add(f.get());
      }
      assertThat(statuses).filteredOn(s -> s == 200).hasSize(5);
      assertThat(statuses).filteredOn(s -> s == 400).hasSize(3);
      mvc.perform(as(owner, get("/products/" + p)).header("X-Branch-Id", c.hq())).andExpect(jsonPath("$.stock").value(0));

      long sn = create("CC3", true);
      mvc.perform(as(owner, post("/products/" + sn + "/serials"), Map.of("serials", List.of("SN-X"), "unitCost", 10))
          .header("X-Branch-Id", c.hq())).andExpect(status().isOk());
      long serialId = JSON.readTree(mvc.perform(as(owner, get("/products/" + sn + "/serials")).header("X-Branch-Id", c.hq()))
          .andReturn().getResponse().getContentAsString()).get(0).path("id").asLong();
      List<Callable<Integer>> removes = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        removes.add(() -> mvc.perform(as(owner, post("/products/" + sn + "/serials/remove"),
                Map.of("ids", List.of(serialId), "status", "sold", "note", "")).header("X-Branch-Id", c.hq()))
            .andReturn().getResponse().getStatus());
      }
      List<Integer> removed = new ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(removes)) {
        removed.add(f.get());
      }
      assertThat(removed).filteredOn(s -> s == 200).hasSize(1);
    } finally {
      pool.shutdown();
    }
  }
}
