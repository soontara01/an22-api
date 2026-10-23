package com.an22.api.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /sales} (ลำดับตรวจ 1–8 ตาม contract), COGS + บัตรสต็อก, void (P3 / D3), ส่วนลดพิเศษ + audit, ใบกำกับเต็มรูป (พร้อมบิล / ทีหลัง /
 * ออกใหม่), รายงานภาษี, ทุนเป็น null สำหรับ user, ขายพร้อมกันเลขไม่ซ้ำ, ข้าม tenant.
 */
class SaleIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  private Company c;
  private String owner;
  private String boss;
  private String staff;
  private long cash;
  private long mouse;
  private long laptop;
  private long setup;
  private long shift;
  private final LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
  private final String day = today.format(DateTimeFormatter.BASIC_ISO_DATE);
  private final String ip = "10.9." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    for (int id : new int[] {1, 2}) {
      mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", id, "name", "เครื่อง " + id, "posId", "P-" + id, "branchId", c.hq(),
          "active", true))).andExpect(status().isOk());
    }
    cash = id(mvc.perform(as(owner, post("/payment-methods"), Map.of("code", "CASH", "name", "เงินสด", "type", "cash", "active", true))));
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    long leaf = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    mouse = sku("MS", leaf, false, "stock", 0);
    laptop = sku("NB", leaf, true, "stock", 0);
    setup = sku("SV", leaf, false, "service", 50);
    price(mouse, 107);
    price(laptop, 25000);
    price(setup, 300);
    mvc.perform(at(1, as(owner, put("/products/" + mouse + "/stock"), Map.of("delta", 10, "unitCost", 60, "note", "รับ"))))
        .andExpect(status().isOk());
    mvc.perform(at(1, as(owner, post("/products/" + laptop + "/serials"), Map.of("serials", List.of("SN-1", "SN-2"), "unitCost", 20000))))
        .andExpect(status().isOk());
    shift = id(mvc.perform(at(1, as(staff, post("/shifts/open"), Map.of("openingFloat", 1000)))).andExpect(status().isOk()));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  private long sku(String code, long category, boolean serial, String itemType, double cost) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("sku", code, "name", "สินค้า " + code, "categoryId", category, "unit", "ชิ้น",
        "serialControl", serial, "serialPrefix", serial ? "SN" : "", "itemType", itemType, "cost", cost));
    return id(mvc.perform(as(owner, post("/products"), m).header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private void price(long product, double price) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("productId", product, "price", price, "startDate", "2020-01-01", "note", ""));
    m.put("endDate", null);
    mvc.perform(as(owner, post("/prices"), m)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder at(int terminal, MockHttpServletRequestBuilder request) {
    return request.header("X-Terminal-Id", terminal).header("X-Branch-Id", c.hq()).header("X-Real-IP", ip);
  }

  private static Map<String, Object> item(long product, int qty, String serial) {
    Map<String, Object> m = new HashMap<>(Map.of("productId", product, "factor", 1, "qty", qty));
    m.put("serial", serial);
    m.put("manualDiscount", null);
    return m;
  }

  private Map<String, Object> sale(List<Map<String, Object>> items, double total, double paid) {
    Map<String, Object> m = new HashMap<>();
    m.put("items", items);
    m.put("freeSerials", List.of());
    m.put("payments", List.of(Map.of("methodId", cash, "amount", paid, "reference", "")));
    m.put("customer", " ลูกค้า ");
    m.put("expectedTotal", total);
    m.put("buyer", null);
    m.put("billManualDiscount", null);
    m.put("manualDiscountReason", "");
    return m;
  }

  private ResultActions sell(String token, int terminal, Map<String, Object> body) throws Exception {
    return mvc.perform(at(terminal, as(token, post("/sales"), body)));
  }

  private static Map<String, Object> buyer() {
    return Map.of("name", " บริษัท ลูกค้า จำกัด ", "taxId", "0-1055-50123-45-1", "branchType", "head", "branchNo", "", "address", "กรุงเทพฯ");
  }

  @Test
  void sellsIssuesStockWithCogsAndCountsInTheShift() throws Exception {
    long id = id(sell(staff, 1, sale(List.of(item(mouse, 2, null), item(laptop, 1, "SN-1"), item(setup, 1, null)), 25514, 26000))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderNo").value("POS-HQ-1-" + day + "-0001")).andExpect(jsonPath("$.status").value("paid"))
        .andExpect(jsonPath("$.cashier").value("staff")).andExpect(jsonPath("$.customer").value("ลูกค้า"))
        .andExpect(jsonPath("$.total").value(25514.0)).andExpect(jsonPath("$.change").value(486.0))
        .andExpect(jsonPath("$.shiftId").value((int) shift)).andExpect(jsonPath("$.posId").value("P-1"))
        .andExpect(jsonPath("$.lines[1].serial").value("SN-1"))
        // staff ไม่เห็นทุน (E43)
        .andExpect(jsonPath("$.lines[0].cogs").value((Object) null)));
    mvc.perform(as(owner, get("/sales/" + id)))
        .andExpect(jsonPath("$.lines[*].cogs", Matchers.contains(120.0, 20000.0, 50.0)))
        .andExpect(jsonPath("$.returnStatus").value("none")).andExpect(jsonPath("$.creditNoteNos.length()").value(0));
    mvc.perform(at(1, as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(8));
    mvc.perform(at(1, as(owner, lastYear(get("/products/" + mouse + "/movements")))))
        .andExpect(jsonPath("$.movements[1].type").value("issue"))
        .andExpect(jsonPath("$.movements[1].note").value("ขาย POS-HQ-1-" + day + "-0001"));
    mvc.perform(at(1, as(owner, get("/products/" + laptop + "/serials"))))
        .andExpect(jsonPath("$[?(@.serial == 'SN-1')].status", Matchers.contains("sold")));
    // ยอดระบบของกะรวมบิลแล้ว
    mvc.perform(as(owner, get("/shifts/" + shift + "/summary")))
        .andExpect(jsonPath("$.billCount").value(1)).andExpect(jsonPath("$.lines[0].expected").value(26514.0));
    mvc.perform(as(staff, get("/sales").param("shiftId", Long.toString(shift)))).andExpect(jsonPath("$[*].id", Matchers.contains((int) id)));
    mvc.perform(at(1, as(staff, lastYear(get("/sales"))))).andExpect(jsonPath("$[0].id").value((int) id));
    mvc.perform(at(1, as(staff, get("/sales")))).andExpect(content().json(message("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"), true));
    // เลขต่อเครื่องต่อวัน
    sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 107)).andExpect(jsonPath("$.orderNo").value("POS-HQ-1-" + day + "-0002"));
    // ประวัติการขาย / ช่องทางที่ใช้แล้ว ลบไม่ได้
    mvc.perform(as(owner, delete("/products/" + setup)))
        .andExpect(content().json(message("SKU นี้มีประวัติการขาย ลบไม่ได้ (เปลี่ยนสถานะเป็นเลิกจำหน่ายแทน)"), true));
    long unused = id(mvc.perform(as(owner, post("/payment-methods"), Map.of("code", "TR", "name", "โอน", "type", "transfer", "active", true)))
        .andExpect(status().isOk()));
    mvc.perform(as(owner, delete("/payment-methods/" + unused))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/payment-methods"), Map.of("code", "CASH2", "name", "เงินสด 2", "type", "cash", "active", true)))
        .andExpect(status().isOk());
    mvc.perform(as(owner, delete("/payment-methods/" + cash)))
        .andExpect(content().json(message("มีการขายที่ใช้ช่องทางนี้แล้ว ลบไม่ได้ (ปิดใช้งานแทน)"), true));
  }

  @Test
  void checksInTheContractOrder() throws Exception {
    Map<String, Object> ok = sale(List.of(item(mouse, 1, null)), 107, 107);
    // 1. เครื่อง
    mvc.perform(as(staff, post("/sales"), ok).header("X-Branch-Id", c.hq())).andExpect(status().isBadRequest())
        .andExpect(content().json(message("เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า"), true));
    // 2. กะ
    sell(boss, 2, ok).andExpect(content().json(message("ยังไม่ได้เปิดกะที่เครื่องนี้"), true));
    sell(boss, 1, ok).andExpect(content().json(message("กะนี้เป็นของ staff — ต้องปิดกะนั้นก่อนจึงจะขายได้"), true));
    // 3. ตะกร้า
    sell(staff, 1, sale(List.of(), 0, 0)).andExpect(content().json(message("ยังไม่มีสินค้าในบิล"), true));
    sell(staff, 1, sale(List.of(item(mouse, 11, null)), 1177, 1177)).andExpect(content().json(message("MS สต็อกไม่พอ (คงเหลือ 10 ชิ้น)"), true));
    // 4. ส่วนลดพิเศษ
    Map<String, Object> discounted = sale(List.of(item(mouse, 1, null)), 97, 97);
    discounted.put("billManualDiscount", Map.of("kind", "amount", "value", 10));
    sell(staff, 1, discounted).andExpect(content().json(message("กรุณาระบุเหตุผลส่วนลดพิเศษ"), true));
    discounted.put("manualDiscountReason", "ลูกค้าประจำ");
    sell(staff, 1, discounted).andExpect(content().json(message("ส่วนลดพิเศษ MS เกิน 5% — ให้ผู้ดูแลระบบทำรายการ"), true));
    // 5. serial
    sell(staff, 1, sale(List.of(item(laptop, 1, "SN-9")), 25000, 25000))
        .andExpect(content().json(message("ซีเรียล SN-9 ไม่อยู่ในคลังของ NB"), true));
    // 6. ยอดที่หน้าจอเห็น
    sell(staff, 1, sale(List.of(item(mouse, 1, null)), 100, 107))
        .andExpect(content().json(message("ราคาหรือโปรโมชั่นมีการเปลี่ยนแปลง กรุณาตรวจสอบบิลอีกครั้ง"), true));
    // 7. การชำระ (+ เลขบัตรเต็ม — PCI DSS)
    sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 100)).andExpect(content().json(message("ยอดชำระยังไม่ครบ (ขาด ฿7)"), true));
    Map<String, Object> pan = sale(List.of(item(mouse, 1, null)), 107, 107);
    pan.put("payments", List.of(Map.of("methodId", cash, "amount", 107, "reference", "4111 1111 1111 1111")));
    sell(staff, 1, pan).andExpect(content().json(message("เงินสด: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย"), true));
    // 8. ผู้ซื้อ
    Map<String, Object> badBuyer = sale(List.of(item(mouse, 1, null)), 107, 107);
    badBuyer.put("buyer", Map.of("name", "x", "taxId", "123", "branchType", "none", "branchNo", "", "address", "y"));
    sell(staff, 1, badBuyer).andExpect(content().json(message("เลขประจำตัวผู้เสียภาษีผู้ซื้อต้องเป็น 13 หลักที่ถูกต้อง"), true));
    // E48: หน้าจอถูกล็อก
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/lock")))).andExpect(status().isOk());
    sell(staff, 1, ok).andExpect(content().json(message("หน้าจอถูกล็อก — ปลดล็อกก่อน"), true));
    // ไม่มีอะไรเปลี่ยน
    mvc.perform(at(1, as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(10));
  }

  @Test
  void manualDiscountByAnAdminIsAuditedAndCostCheckWarnsWithoutNumbers() throws Exception {
    long ownShift = id(mvc.perform(at(2, as(owner, post("/shifts/open"), Map.of("openingFloat", 0)))));
    assertThat(ownShift).isPositive();
    Map<String, Object> body = sale(List.of(item(mouse, 1, null)), 57, 57);
    body.put("billManualDiscount", Map.of("kind", "amount", "value", 50));
    body.put("manualDiscountReason", " ลูกค้าประจำ ");
    // ขายต่ำกว่าทุน (60) หลังส่วนลด = เตือนเท่านั้น
    mvc.perform(at(2, as(staff, post("/sales/cost-check"), body)))
        .andExpect(jsonPath("$.issues[0].message").value("MS ขายต่ำกว่าทุนหลังส่วนลดพิเศษ"))
        .andExpect(jsonPath("$.issues[0].blocking").value(false));
    sell(owner, 2, body).andExpect(status().isOk()).andExpect(jsonPath("$.manualDiscount").value(50.0))
        .andExpect(jsonPath("$.manualDiscountBy").value("owner")).andExpect(jsonPath("$.manualDiscountReason").value("ลูกค้าประจำ"));
    assertThat(companies.audit(c, "manual_discount")).containsExactly("manual_discount|POS-HQ-2-" + day + "-0001|฿50 — ลูกค้าประจำ");
  }

  @Test
  void voidNeedsAnAdminAndPutsGoodsBack() throws Exception {
    Map<String, Object> body = sale(List.of(item(mouse, 2, null), item(laptop, 1, "SN-1")), 25214, 25214);
    body.put("buyer", buyer());
    long id = id(sell(staff, 1, body).andExpect(jsonPath("$.taxInvoiceNo").value("INV-HQ-" + day + "-0001")));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/void"), Map.of("reason", "ลูกค้าเปลี่ยนใจ")))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ยกเลิกบิลต้องให้ผู้ดูแลระบบอนุมัติ"), true));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/void"),
            Map.of("reason", "ลูกค้าเปลี่ยนใจ", "approval", Map.of("username", "boss", "password", "ผิด")))))
        .andExpect(content().json(message("รหัสผ่านผู้อนุมัติไม่ถูกต้อง"), true));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/void"),
            Map.of("reason", " ", "approval", Map.of("username", "boss", "password", TestCompanies.PASSWORD)))))
        .andExpect(content().json(message("กรุณาระบุเหตุผลการยกเลิก"), true));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/void"),
            Map.of("reason", " ลูกค้าเปลี่ยนใจ ", "approval", Map.of("username", "boss", "password", TestCompanies.PASSWORD)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"))
        .andExpect(jsonPath("$.voidedBy").value("staff")).andExpect(jsonPath("$.voidApprovedBy").value("boss"))
        .andExpect(jsonPath("$.voidShiftId").value((int) shift));
    mvc.perform(at(1, as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(10)).andExpect(jsonPath("$.avgCost").value(60.0));
    mvc.perform(at(1, as(owner, get("/products/" + laptop + "/serials"))))
        .andExpect(jsonPath("$[?(@.serial == 'SN-1')].status", Matchers.contains("in_stock")));
    mvc.perform(as(owner, get("/sales/" + id + "/tax-invoice")))
        .andExpect(jsonPath("$.cancelReason").value("ยกเลิกบิล: ลูกค้าเปลี่ยนใจ")).andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    mvc.perform(at(1, as(owner, post("/sales/" + id + "/void"), Map.of("reason", "x")))).andExpect(content().json(message("ยกเลิกได้เฉพาะบิลที่ชำระแล้ว"), true));
    assertThat(companies.audit(c, "sale_void")).containsExactly("sale_void|POS-HQ-1-" + day + "-0001|฿25214 — ลูกค้าเปลี่ยนใจ (อนุมัติโดย boss)");
    // ยอดระบบของกะ: ขายแล้วยกเลิกในกะเดียวกัน = 0
    mvc.perform(as(owner, get("/shifts/" + shift + "/summary"))).andExpect(jsonPath("$.lines[0].expected").value(1000.0))
        .andExpect(jsonPath("$.voidCount").value(1));
  }

  @Test
  void taxInvoicesAtSaleLaterAndReissued() throws Exception {
    long id = id(sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 107)).andExpect(jsonPath("$.taxInvoiceNo").value((Object) null)));
    mvc.perform(as(staff, get("/sales/" + id + "/tax-invoice"))).andExpect(content().string(""));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/tax-invoice"), Map.of("buyer", buyer()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceNo").value("INV-HQ-" + day + "-0001"))
        .andExpect(jsonPath("$.atSale").value(false)).andExpect(jsonPath("$.buyer.taxId").value("0105550123451"))
        .andExpect(jsonPath("$.buyer.name").value("บริษัท ลูกค้า จำกัด")).andExpect(jsonPath("$.issuedBy").value("staff"));
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/tax-invoice"), Map.of("buyer", buyer()))))
        .andExpect(content().json(message("บิลนี้ออกใบกำกับภาษีเต็มรูปแล้ว (INV-HQ-" + day + "-0001)"), true));
    // ข้อมูลผู้ซื้ออยู่ใน DB แบบเข้ารหัส + ค้นได้ด้วยเลขเต็ม
    byte[] stored = sql.platform(jdbc -> jdbc.sql("SELECT buyer_enc FROM dt_tax_invoices WHERE tenant_id = :t").param("t", c.id())
        .query(byte[].class).single());
    assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("0105550123451").doesNotContain("ลูกค้า");
    mvc.perform(as(staff, get("/tax-invoices/buyer").param("taxId", "0105550123451")))
        .andExpect(jsonPath("$.name").value("บริษัท ลูกค้า จำกัด"));
    mvc.perform(as(staff, get("/tax-invoices/buyer").param("taxId", "0105550123"))).andExpect(content().string(""));

    Map<String, Object> fixed = Map.of("buyer", Map.of("name", "บริษัท ลูกค้าใหม่ จำกัด", "taxId", "0105550123451", "branchType", "branch",
        "branchNo", "00002", "address", "กรุงเทพฯ"), "reason", " ชื่อผิด ");
    mvc.perform(at(1, as(staff, post("/sales/" + id + "/tax-invoice/reissue"), fixed))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ยกเลิกและออกใบกำกับภาษีใหม่ได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(at(1, as(owner, post("/sales/" + id + "/tax-invoice/reissue"), fixed)))
        .andExpect(jsonPath("$.invoiceNo").value("INV-HQ-" + day + "-0002"))
        .andExpect(jsonPath("$.replacesInvoiceNo").value("INV-HQ-" + day + "-0001")).andExpect(jsonPath("$.buyer.branchNo").value("00002"));
    mvc.perform(as(owner, get("/sales/" + id + "/tax-invoices")))
        .andExpect(jsonPath("$[0].cancelReason").value("ชื่อผิด")).andExpect(jsonPath("$[0].replacedByNo").value("INV-HQ-" + day + "-0002"))
        .andExpect(jsonPath("$[1].cancelledAt").value((Object) null));
    mvc.perform(as(owner, get("/sales/" + id))).andExpect(jsonPath("$.taxInvoiceNo").value("INV-HQ-" + day + "-0002"));
    assertThat(companies.audit(c, "tax_invoice_reissue"))
        .containsExactly("tax_invoice_reissue|INV-HQ-" + day + "-0002|แทน INV-HQ-" + day + "-0001 — ชื่อผิด");

    mvc.perform(at(1, as(staff, lastYear(get("/tax-invoices"))))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูรายงานภาษีขายได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(at(1, as(owner, lastYear(get("/tax-invoices")))))
        .andExpect(jsonPath("$[*].invoiceNo", Matchers.contains("INV-HQ-" + day + "-0001", "INV-HQ-" + day + "-0002")));
  }

  @Test
  void preposOrdersChangeStatusAndPosBillsDoNot() throws Exception {
    String admin = login("DEMO", "admin", "admin");
    mvc.perform(as(admin, get("/sales").param("from", "2026-09-01").param("to", "2026-10-02").param("branchId", "1")))
        .andExpect(jsonPath("$[*].orderNo", Matchers.hasItems("SO-2026-0001", "SO-2026-0007")))
        .andExpect(jsonPath("$[0].lines.length()").value(0));
    long id = id(sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 107)));
    mvc.perform(as(owner, put("/sales/" + id + "/status"), Map.of("status", "pending")))
        .andExpect(content().json(message("บิลขายหน้าร้านเปลี่ยนสถานะไม่ได้ (ใช้การยกเลิกบิล)"), true));
  }

  @Test
  void concurrentSalesGetDistinctNumbers() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Callable<String> call = () -> JSON.readTree(sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 107)).andReturn()
            .getResponse().getContentAsString()).path("orderNo").asString();
        results.add(pool.submit(call));
      }
      List<String> numbers = new ArrayList<>();
      for (Future<String> f : results) {
        numbers.add(f.get());
      }
      assertThat(numbers).containsExactlyInAnyOrder("POS-HQ-1-" + day + "-0001", "POS-HQ-1-" + day + "-0002",
          "POS-HQ-1-" + day + "-0003", "POS-HQ-1-" + day + "-0004");
    } finally {
      pool.shutdown();
    }
    mvc.perform(at(1, as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(6));
  }

  @Test
  void anotherCompanysSaleIsNotFound() throws Exception {
    long id = id(sell(staff, 1, sale(List.of(item(mouse, 1, null)), 107, 107)));
    Company other = companies.create();
    String stranger = login(other.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(stranger, get("/sales/" + id))).andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบบิลขาย"), true));
    mvc.perform(as(stranger, post("/sales/" + id + "/void"), Map.of("reason", "x")).header("X-Branch-Id", other.hq()))
        .andExpect(status().isNotFound());
    mvc.perform(as(stranger, get("/sales/" + id + "/tax-invoices"))).andExpect(status().isNotFound());
    mvc.perform(as(stranger, post("/sales/" + id + "/tax-invoice"), Map.of("buyer", buyer())).header("X-Branch-Id", other.hq()))
        .andExpect(status().isNotFound());
    mvc.perform(as(stranger, lastYear(get("/sales"))).param("branchId", "all")).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(stranger, get("/tax-invoices/buyer").param("taxId", "0105550123451"))).andExpect(content().string(""));
  }
}
