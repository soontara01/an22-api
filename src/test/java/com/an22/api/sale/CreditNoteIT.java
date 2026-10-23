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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ใบลดหนี้ (admin, ไม่ใช่วันขาย, คืนสต็อกที่ COGS / ตัดเสีย, field derived ของบิล, ยอดของกะ, audit) + ใบเปลี่ยน (serial ที่ลูกค้าถือ, ทุนเข้า /
 * ออก) + ข้าม tenant.
 */
class CreditNoteIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  private Company c;
  private String owner;
  private String staff;
  private long cash;
  private long mouse;
  private long laptop;
  private long shift;
  private final String day = LocalDate.now(ClockConfig.BANGKOK).format(DateTimeFormatter.BASIC_ISO_DATE);

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", 1, "name", "เครื่อง 1", "posId", "", "branchId", c.hq(), "active", true)))
        .andExpect(status().isOk());
    cash = id(mvc.perform(as(owner, post("/payment-methods"), Map.of("code", "CASH", "name", "เงินสด", "type", "cash", "active", true))));
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    long leaf = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
    mouse = sku("MS", leaf, false);
    laptop = sku("NB", leaf, true);
    price(mouse, 107);
    price(laptop, 25000);
    mvc.perform(at(as(owner, put("/products/" + mouse + "/stock"), Map.of("delta", 10, "unitCost", 60, "note", "รับ")))).andExpect(status().isOk());
    mvc.perform(at(as(owner, post("/products/" + laptop + "/serials"), Map.of("serials", List.of("SN-1", "SN-2", "SN-3"), "unitCost", 20000))))
        .andExpect(status().isOk());
    shift = id(mvc.perform(at(as(staff, post("/shifts/open"), Map.of("openingFloat", 1000)))).andExpect(status().isOk()));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  private long sku(String code, long category, boolean serial) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("sku", code, "name", "สินค้า " + code, "categoryId", category, "unit", "ชิ้น",
        "serialControl", serial, "serialPrefix", serial ? "SN" : ""));
    return id(mvc.perform(as(owner, post("/products"), m).header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private void price(long product, double price) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("productId", product, "price", price, "startDate", "2020-01-01", "note", ""));
    m.put("endDate", null);
    mvc.perform(as(owner, post("/prices"), m)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder at(MockHttpServletRequestBuilder request) {
    return request.header("X-Terminal-Id", 1).header("X-Branch-Id", c.hq());
  }

  private static Map<String, Object> item(long product, int qty, String serial) {
    Map<String, Object> m = new HashMap<>(Map.of("productId", product, "factor", 1, "qty", qty));
    m.put("serial", serial);
    return m;
  }

  /** ขายแล้ว (ถ้า {@code yesterday}) ย้ายเวลาบิลไปเมื่อวาน — ใบลดหนี้ออกได้ตั้งแต่วันถัดจากวันขาย. */
  private long sell(List<Map<String, Object>> items, double total, boolean yesterday) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("items", items);
    body.put("freeSerials", List.of());
    body.put("payments", List.of(Map.of("methodId", cash, "amount", total, "reference", "")));
    body.put("customer", "");
    body.put("expectedTotal", total);
    long id = id(mvc.perform(at(as(staff, post("/sales"), body))).andExpect(status().isOk()));
    if (yesterday) {
      sql.platform(jdbc -> jdbc.sql("UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = :id").param("id", id).update());
    }
    return id;
  }

  private static Map<String, Object> ret(int index, int qty, boolean restock) {
    return Map.of("saleLineIndex", index, "qty", qty, "restock", restock);
  }

  private ResultActions credit(String token, long sale, List<Map<String, Object>> lines, double total, String reference) throws Exception {
    Map<String, Object> body = Map.of("lines", lines, "refunds", List.of(Map.of("methodId", cash, "amount", total, "reference", reference)),
        "reason", " ชำรุด ", "expectedTotal", total);
    return mvc.perform(at(as(token, post("/sales/" + sale + "/credit-notes"), body)));
  }

  @Test
  void creditNotesRefundAShareRestockAtCogsAndDeriveTheReturnStatus() throws Exception {
    long sale = sell(List.of(item(mouse, 3, null), item(laptop, 1, "SN-1")), 25321, true);
    credit(staff, sale, List.of(ret(0, 1, true)), 107, "").andExpect(status().isForbidden())
        .andExpect(content().json(message("ออกใบลดหนี้ได้เฉพาะผู้ดูแลระบบ"), true));
    credit(owner, sale, List.of(ret(0, 1, true)), 107, "4111 1111 1111 1111")
        .andExpect(content().json(message("เงินสด: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย"), true));
    credit(owner, sale, List.of(ret(0, 4, true)), 428, "").andExpect(content().json(message("MS คืนได้ไม่เกิน 3 ชิ้น"), true));
    credit(owner, sale, List.of(ret(0, 1, true)), 107, "").andExpect(status().isOk())
        .andExpect(jsonPath("$.cnNo").value("CN-HQ-" + day + "-0001")).andExpect(jsonPath("$.total").value(107.0))
        .andExpect(jsonPath("$.reason").value("ชำรุด")).andExpect(jsonPath("$.shiftId").value((int) shift))
        .andExpect(jsonPath("$.lines[0].cogs").value(60.0)).andExpect(jsonPath("$.refunds[0].amount").value(107.0))
        .andExpect(jsonPath("$.buyer").value((Object) null));
    mvc.perform(at(as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(8)).andExpect(jsonPath("$.avgCost").value(60.0));
    mvc.perform(as(owner, get("/sales/" + sale))).andExpect(jsonPath("$.returnStatus").value("partial"))
        .andExpect(jsonPath("$.creditedTotal").value(107.0)).andExpect(jsonPath("$.creditNoteNos[0]").value("CN-HQ-" + day + "-0001"));
    // เครื่องคืนแต่ไม่เข้าคลัง (ตัดเสีย) + เมาส์ที่เหลือ = คืนทั้งบิล
    credit(owner, sale, List.of(ret(0, 2, true), ret(1, 1, false)), 25214, "").andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[1].serial").value("SN-1")).andExpect(jsonPath("$.lines[1].restock").value(false));
    mvc.perform(at(as(owner, get("/products/" + laptop + "/serials"))))
        .andExpect(jsonPath("$[?(@.serial == 'SN-1')].status", Matchers.contains("damaged")));
    mvc.perform(as(owner, get("/sales/" + sale))).andExpect(jsonPath("$.returnStatus").value("full"))
        .andExpect(jsonPath("$.creditNoteNos.length()").value(2)).andExpect(jsonPath("$.creditedTotal").value(25321.0));
    credit(owner, sale, List.of(ret(0, 1, true)), 107, "").andExpect(content().json(message("MS คืนได้ไม่เกิน 0 ชิ้น"), true));
    // ยอดระบบของกะ: คืนเงินจากลิ้นชักของกะที่ออกใบ
    mvc.perform(as(owner, get("/shifts/" + shift + "/summary"))).andExpect(jsonPath("$.refundCount").value(2))
        .andExpect(jsonPath("$.refundTotal").value(25321.0));
    mvc.perform(as(owner, get("/credit-notes").param("shiftId", Long.toString(shift)))).andExpect(jsonPath("$.length()").value(2));
    mvc.perform(at(as(owner, lastYear(get("/credit-notes"))))).andExpect(jsonPath("$[*].cnNo",
        Matchers.contains("CN-HQ-" + day + "-0002", "CN-HQ-" + day + "-0001")));
    mvc.perform(at(as(owner, get("/credit-notes")))).andExpect(content().json(message("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"), true));
    assertThat(companies.audit(c, "credit_note")).containsExactly(
        "credit_note|CN-HQ-" + day + "-0001|บิล " + orderNo(sale) + " คืนเงิน ฿107",
        "credit_note|CN-HQ-" + day + "-0002|บิล " + orderNo(sale) + " คืนเงิน ฿25214");
  }

  private String orderNo(long sale) throws Exception {
    return JSON.readTree(mvc.perform(as(owner, get("/sales/" + sale))).andReturn().getResponse().getContentAsString()).path("orderNo")
        .asString();
  }

  @Test
  void todaysBillIsVoidedNotCredited() throws Exception {
    long sale = sell(List.of(item(mouse, 1, null)), 107, false);
    credit(owner, sale, List.of(ret(0, 1, true)), 107, "").andExpect(status().isBadRequest())
        .andExpect(content().json(message("บิลของวันนี้ให้ยกเลิกบิลแทนการออกใบลดหนี้"), true));
  }

  @Test
  void exchangesSwapStockAndTheSerialTheCustomerHolds() throws Exception {
    long sale = sell(List.of(item(mouse, 2, null), item(laptop, 1, "SN-1")), 25214, true);
    Map<String, Object> body = Map.of("lines", List.of(Map.of("saleLineIndex", 1, "qty", 1, "newSerial", " SN-1 ", "restock", true)),
        "reason", "เครื่องเสีย");
    mvc.perform(at(as(staff, post("/sales/" + sale + "/exchanges"), body)))
        .andExpect(content().json(message("NB: ซีเรียลใหม่ต้องไม่ใช่เครื่องเดิม"), true));
    Map<String, Object> lines = new HashMap<>(Map.of("saleLineIndex", 1, "qty", 1, "newSerial", "SN-2", "restock", true));
    Map<String, Object> mice = new HashMap<>(Map.of("saleLineIndex", 0, "qty", 1, "restock", false));
    mice.put("newSerial", null);
    mvc.perform(at(as(staff, post("/sales/" + sale + "/exchanges"), Map.of("lines", List.of(lines, mice), "reason", " เครื่องเสีย "))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.exNo").value("EX-HQ-" + day + "-0001"))
        .andExpect(jsonPath("$.lines[0].oldSerial").value("SN-1")).andExpect(jsonPath("$.lines[0].newSerial").value("SN-2"))
        .andExpect(jsonPath("$.shiftId").value((int) shift))
        // staff ไม่เห็นทุน
        .andExpect(jsonPath("$.lines[0].costIn").value((Object) null));
    mvc.perform(as(owner, get("/sales/" + sale + "/exchanges")))
        .andExpect(jsonPath("$[0].lines[*].costIn", Matchers.contains(20000.0, 60.0)))
        .andExpect(jsonPath("$[0].lines[*].costOut", Matchers.contains(20000.0, 60.0)));
    mvc.perform(at(as(owner, get("/products/" + laptop + "/serials"))))
        .andExpect(jsonPath("$[?(@.serial == 'SN-1')].status", Matchers.contains("in_stock")))
        .andExpect(jsonPath("$[?(@.serial == 'SN-2')].status", Matchers.contains("sold")));
    // เมาส์: ของใหม่ออก 1, ของเดิมไม่เข้าคลัง
    mvc.perform(at(as(owner, get("/products/" + mouse)))).andExpect(jsonPath("$.stock").value(7));
    mvc.perform(as(owner, get("/sales/" + sale))).andExpect(jsonPath("$.exchangeNos[0]").value("EX-HQ-" + day + "-0001"));
    // ใบลดหนี้ของบรรทัดนี้คืนเครื่องที่ลูกค้าถืออยู่ตอนนี้
    credit(owner, sale, List.of(ret(1, 1, true)), 25000, "").andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].serial").value("SN-2"));
    mvc.perform(as(owner, get("/shifts/" + shift + "/summary"))).andExpect(jsonPath("$.exchangeCount").value(1));
  }

  @Test
  void anotherCompanysCreditNotesAndExchangesAreNotFound() throws Exception {
    long sale = sell(List.of(item(mouse, 1, null)), 107, true);
    Company other = companies.create();
    String stranger = login(other.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(stranger, get("/sales/" + sale + "/credit-notes"))).andExpect(status().isNotFound());
    mvc.perform(as(stranger, post("/sales/" + sale + "/credit-notes"), Map.of("lines", List.of(ret(0, 1, true)), "refunds", List.of(),
        "reason", "x", "expectedTotal", 107)).header("X-Branch-Id", other.hq())).andExpect(status().isNotFound());
    mvc.perform(as(stranger, post("/sales/" + sale + "/exchanges"), Map.of("lines", List.of(), "reason", "x"))
        .header("X-Branch-Id", other.hq())).andExpect(status().isNotFound());
    mvc.perform(as(stranger, lastYear(get("/credit-notes"))).param("branchId", "all")).andExpect(jsonPath("$.length()").value(0));
  }
}
