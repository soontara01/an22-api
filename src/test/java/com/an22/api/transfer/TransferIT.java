package com.an22.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
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

/** {@code /transfers} — โอน non-serial / serial, รับขาด (E28), ยกเลิก (E29), สิทธิ์ต้นทาง / ปลายทาง, เลขเอกสาร, E18, ข้าม tenant. */
class TransferIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String boss;
  private String staff;
  private long category;
  private final String prefix = "TR-HQ-" + LocalDate.now(ClockConfig.BANGKOK).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    long root = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R", "name", "ราก", "active", true))));
    category = id(mvc.perform(as(owner, post("/categories"), Map.of("code", "R-1", "name", "ย่อย", "parentId", root, "active", true))));
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  private long sku(String code, boolean serial) throws Exception {
    Map<String, Object> m = new HashMap<>(Map.of("sku", code, "name", "สินค้า " + code, "categoryId", category, "unit", "ชิ้น"));
    m.put("serialControl", serial);
    m.put("serialPrefix", serial ? "SN" : "");
    return id(mvc.perform(as(owner, post("/products"), m).header("X-Branch-Id", c.hq())).andExpect(status().isOk()));
  }

  private void stockIn(long branch, long product, int qty, double cost) throws Exception {
    mvc.perform(as(owner, put("/products/" + product + "/stock"), Map.of("delta", qty, "unitCost", cost, "note", "รับ"))
        .header("X-Branch-Id", branch)).andExpect(status().isOk());
  }

  private ResultActions stockAt(long branch, long product) throws Exception {
    return mvc.perform(as(owner, get("/products/" + product)).header("X-Branch-Id", branch));
  }

  private static Map<String, Object> line(long product, Object qty, List<String> serials) {
    return Map.of("productId", product, "qty", qty, "serials", serials);
  }

  private ResultActions send(String token, long from, long to, List<Map<String, Object>> lines) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("toBranchId", to);
    body.put("lines", lines);
    body.put("note", " ส่งของ ");
    return mvc.perform(as(token, post("/transfers"), body).header("X-Branch-Id", from));
  }

  private ResultActions receive(String token, long branch, long transfer, List<Map<String, Object>> lines, String reason)
      throws Exception {
    return mvc.perform(as(token, post("/transfers/" + transfer + "/receive"), Map.of("lines", lines, "shortReason", reason))
        .header("X-Branch-Id", branch));
  }

  private ResultActions cancel(String token, long branch, long transfer, String reason) throws Exception {
    return mvc.perform(as(token, post("/transfers/" + transfer + "/cancel"), Map.of("reason", reason)).header("X-Branch-Id", branch));
  }

  @Test
  void nonSerialGoesOutAtTheSourceAverageAndShortfallIsWrittenOffAtTheDestination() throws Exception {
    long p = sku("P", false);
    stockIn(c.hq(), p, 10, 100);
    stockIn(c.bn(), p, 5, 200);
    send(owner, c.hq(), c.bn(), List.of(line(p, 11, List.of())))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("P มีสต็อกไม่พอ (คงเหลือ 10 ชิ้น)"), true));
    send(owner, c.hq(), c.hq(), List.of(line(p, 1, List.of())))
        .andExpect(content().json(message("สาขาปลายทางต้องไม่ใช่สาขาต้นทาง"), true));
    send(owner, c.hq(), c.old(), List.of(line(p, 1, List.of()))).andExpect(content().json(message("สาขา OLD ปิดใช้งานแล้ว"), true));
    send(owner, c.hq(), c.bn(), List.of(line(p, 1.5, List.of())))
        .andExpect(content().json(message("P: จำนวนต้องเป็นจำนวนเต็มมากกว่า 0"), true));
    mvc.perform(as(owner, post("/transfers"), Map.of("toBranchId", c.bn(), "lines", List.of(line(p, 1, List.of())))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"), true));

    long t = id(send(staff, c.hq(), c.bn(), List.of(line(p, 4, List.of()))).andExpect(status().isOk())
        .andExpect(jsonPath("$.transferNo").value(prefix + "0001")).andExpect(jsonPath("$.status").value("in_transit"))
        .andExpect(jsonPath("$.createdBy").value("staff")).andExpect(jsonPath("$.note").value("ส่งของ"))
        .andExpect(jsonPath("$.lines[0].sku").value("P")).andExpect(jsonPath("$.lines[0].receivedQty").value((Object) null))
        // staff ไม่เห็นทุน (E43)
        .andExpect(jsonPath("$.lines[0].unitCost").value((Object) null)));
    mvc.perform(as(owner, get("/transfers/" + t))).andExpect(jsonPath("$.lines[0].unitCost").value(100.0))
        .andExpect(jsonPath("$.receivedAt").value((Object) null));
    stockAt(c.hq(), p).andExpect(jsonPath("$.stock").value(6)).andExpect(jsonPath("$.avgCost").value(100.0));

    receive(owner, c.hq(), t, List.of(Map.of("productId", p, "receivedQty", 4, "receivedSerials", List.of())), "")
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รับของได้ที่สาขาปลายทางเท่านั้น"), true));
    receive(owner, c.bn(), t, List.of(Map.of("productId", p, "receivedQty", 3, "receivedSerials", List.of())), " ")
        .andExpect(content().json(message("กรุณาระบุเหตุผลของสินค้าที่ขาด / เสียหาย"), true));
    receive(owner, c.bn(), t, List.of(Map.of("productId", p, "receivedQty", 3, "receivedSerials", List.of())), "กล่องแตก")
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("received"))
        .andExpect(jsonPath("$.receivedBy").value("owner")).andExpect(jsonPath("$.shortReason").value("กล่องแตก"))
        .andExpect(jsonPath("$.lines[0].receivedQty").value(3));
    // เข้าเต็ม 4 ที่ทุนโอน 100 → (5×200 + 4×100) / 9, แล้วตัดที่ขาด 1 ที่ทุนเฉลี่ยนั้น
    stockAt(c.bn(), p).andExpect(jsonPath("$.stock").value(8)).andExpect(jsonPath("$.avgCost").value(155.5556));
    mvc.perform(as(owner, lastYear(get("/products/" + p + "/movements"))).header("X-Branch-Id", c.bn()))
        .andExpect(jsonPath("$.movements[*].type", Matchers.contains("receive", "transfer_in", "issue")))
        .andExpect(jsonPath("$.movements[1].note").value("รับโอนจากสาขา HQ " + prefix + "0001"))
        .andExpect(jsonPath("$.movements[2].note").value("ขาด/เสียหายจากการโอน " + prefix + "0001 · กล่องแตก"));
    mvc.perform(as(owner, lastYear(get("/products/" + p + "/movements"))).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$.movements[1].type").value("transfer_out"))
        .andExpect(jsonPath("$.movements[1].note").value("โอนไปสาขา BN " + prefix + "0001"));
    receive(owner, c.bn(), t, List.of(Map.of("productId", p, "receivedQty", 4, "receivedSerials", List.of())), "")
        .andExpect(content().json(message("ใบโอนนี้รับหรือยกเลิกไปแล้ว"), true));

    // ใบที่สองของวันเดียวกัน
    send(owner, c.hq(), c.bn(), List.of(line(p, 1, List.of()))).andExpect(jsonPath("$.transferNo").value(prefix + "0002"));
  }

  @Test
  void serialsTravelInTransitAndLostOnesAreDamagedAtTheDestination() throws Exception {
    long s = sku("S", true);
    mvc.perform(as(owner, post("/products/" + s + "/serials"), Map.of("serials", List.of("SN-1", "SN-2"), "unitCost", 1000))
        .header("X-Branch-Id", c.hq())).andExpect(status().isOk());
    mvc.perform(as(owner, post("/products/" + s + "/serials"), Map.of("serials", List.of("SN-3"), "unitCost", 1300))
        .header("X-Branch-Id", c.hq())).andExpect(status().isOk());
    send(owner, c.hq(), c.bn(), List.of(line(s, 2, List.of("SN-1", "SN-9"))))
        .andExpect(content().json(message("ซีเรียล SN-9 ไม่อยู่ในคลังของ S ที่สาขานี้"), true));
    long t = id(send(owner, c.hq(), c.bn(), List.of(line(s, 2, List.of(" sn-1", "SN-3")))).andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[0].serials", Matchers.contains("SN-1", "SN-3")))
        .andExpect(jsonPath("$.lines[0].unitCost").value(1150.0)));
    stockAt(c.hq(), s).andExpect(jsonPath("$.stock").value(1)).andExpect(jsonPath("$.avgCost").value(1000.0));
    // ระหว่างโอนขายไม่ได้ทั้งสองสาขา และรับเข้าซ้ำไม่ได้
    send(owner, c.hq(), c.bn(), List.of(line(s, 1, List.of("SN-3"))))
        .andExpect(content().json(message("ซีเรียล SN-3 ไม่อยู่ในคลังของ S ที่สาขานี้"), true));

    receive(owner, c.bn(), t, List.of(Map.of("productId", s, "receivedQty", 1, "receivedSerials", List.of("SN-1"))), "หาย")
        .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].receivedSerials", Matchers.contains("SN-1")));
    stockAt(c.bn(), s).andExpect(jsonPath("$.stock").value(1)).andExpect(jsonPath("$.avgCost").value(1000.0));
    mvc.perform(as(owner, get("/products/" + s + "/serials")).header("X-Branch-Id", c.bn()))
        .andExpect(jsonPath("$[*].serial", Matchers.contains("SN-1", "SN-3")))
        .andExpect(jsonPath("$[*].status", Matchers.contains("in_stock", "damaged")));
    mvc.perform(as(owner, lastYear(get("/products/" + s + "/movements"))).header("X-Branch-Id", c.bn()))
        .andExpect(jsonPath("$.movements[*].type", Matchers.contains("transfer_in", "issue")))
        .andExpect(jsonPath("$.movements[1].serials", Matchers.contains("SN-3")))
        .andExpect(jsonPath("$.movements[1].totalCost").value(-1300.0));
  }

  @Test
  void onlyTheSourceAdminCancelsAndGoodsReturnAtTheTransferCost() throws Exception {
    long p = sku("P", false);
    stockIn(c.hq(), p, 2, 100);
    long t = id(send(staff, c.hq(), c.bn(), List.of(line(p, 2, List.of()))).andExpect(status().isOk()));
    // E18: ของระหว่างโอนนับเป็นสต็อก
    mvc.perform(as(owner, delete("/products/" + p)))
        .andExpect(content().json(message("ยังมีสต็อกคงเหลือ (ระหว่างโอน " + prefix + "0001) ลบไม่ได้"), true));
    cancel(staff, c.hq(), t, "ส่งผิด").andExpect(status().isForbidden())
        .andExpect(content().json(message("ยกเลิกใบโอนได้เฉพาะผู้ดูแลระบบ"), true));
    cancel(owner, c.bn(), t, "ส่งผิด").andExpect(content().json(message("ยกเลิกได้ที่สาขาต้นทางเท่านั้น"), true));
    cancel(boss, c.hq(), t, " ").andExpect(content().json(message("กรุณาระบุเหตุผลการยกเลิก"), true));
    cancel(boss, c.hq(), t, "ส่งผิด").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"))
        .andExpect(jsonPath("$.cancelledBy").value("boss")).andExpect(jsonPath("$.cancelReason").value("ส่งผิด"));
    stockAt(c.hq(), p).andExpect(jsonPath("$.stock").value(2)).andExpect(jsonPath("$.avgCost").value(100.0));
    cancel(boss, c.hq(), t, "ส่งผิด").andExpect(content().json(message("ยกเลิกได้เฉพาะใบโอนที่ยังไม่ได้รับ"), true));
    receive(owner, c.bn(), t, List.of(Map.of("productId", p, "receivedQty", 2, "receivedSerials", List.of())), "")
        .andExpect(content().json(message("ใบโอนนี้รับหรือยกเลิกไปแล้ว"), true));
    assertThat(companies.audit(c, "transfer_cancel")).containsExactly("transfer_cancel|" + prefix + "0001|ส่งผิด");
  }

  @Test
  void listsFollowTheBranchScope() throws Exception {
    long p = sku("P", false);
    stockIn(c.hq(), p, 5, 100);
    stockIn(c.bn(), p, 5, 100);
    long other = id(mvc.perform(as(owner, post("/branches"), Map.of("code", "B3", "name", "สาขา 3", "branchNo", "00009", "address", "x")))
        .andExpect(status().isOk()));
    long out = id(send(owner, c.hq(), c.bn(), List.of(line(p, 1, List.of()))));
    long in = id(send(owner, c.bn(), c.hq(), List.of(line(p, 1, List.of()))));
    long elsewhere = id(send(owner, c.bn(), other, List.of(line(p, 1, List.of()))));
    mvc.perform(as(staff, get("/transfers")).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$[*].id", Matchers.contains((int) in, (int) out)));
    mvc.perform(as(staff, get("/transfers").param("direction", "out")).header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$[*].id", Matchers.contains((int) out)));
    mvc.perform(as(owner, get("/transfers").param("branchId", "all").param("direction", "in")))
        .andExpect(jsonPath("$[*].id", Matchers.contains((int) elsewhere, (int) in, (int) out)));
    mvc.perform(as(staff, get("/transfers").param("branchId", "all"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท"), true));
    mvc.perform(as(staff, get("/transfers").param("branchId", Long.toString(c.bn())))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ไม่มีสิทธิ์ดูข้อมูลของสาขานี้"), true));
    mvc.perform(as(staff, get("/transfers").param("branchId", "x"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("รหัสสาขาไม่ถูกต้อง"), true));
    mvc.perform(as(staff, get("/transfers/" + elsewhere))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ไม่มีสิทธิ์ดูใบโอนของสาขานี้"), true));
    mvc.perform(as(staff, get("/transfers/" + in))).andExpect(status().isOk());
  }

  @Test
  void concurrentReceivesHappenOnce() throws Exception {
    long p = sku("P", false);
    stockIn(c.hq(), p, 5, 100);
    long t = id(send(owner, c.hq(), c.bn(), List.of(line(p, 5, List.of()))));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Callable<Integer> call = () -> receive(owner, c.bn(), t,
            List.of(Map.of("productId", p, "receivedQty", 5, "receivedSerials", List.of())), "").andReturn().getResponse().getStatus();
        results.add(pool.submit(call));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : results) {
        statuses.add(f.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 400, 400, 400);
    } finally {
      pool.shutdown();
    }
    stockAt(c.bn(), p).andExpect(jsonPath("$.stock").value(5));
  }

  @Test
  void anotherCompanysTransfersAreNotFound() throws Exception {
    long p = sku("P", false);
    stockIn(c.hq(), p, 5, 100);
    long t = id(send(owner, c.hq(), c.bn(), List.of(line(p, 1, List.of()))));
    Company other = companies.create();
    String stranger = login(other.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(stranger, get("/transfers/" + t))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบใบโอน"), true));
    receive(stranger, other.bn(), t, List.of(Map.of("productId", p, "receivedQty", 1, "receivedSerials", List.of())), "")
        .andExpect(status().isNotFound());
    cancel(stranger, other.hq(), t, "x").andExpect(status().isNotFound());
    mvc.perform(as(stranger, get("/transfers").param("branchId", "all"))).andExpect(jsonPath("$.length()").value(0));
    // SKU ของบริษัทอื่น = ไม่พบ; ปลายทางเป็นสาขาของบริษัทอื่น = ไม่มีสาขานั้น
    send(stranger, other.hq(), other.bn(), List.of(line(p, 1, List.of())))
        .andExpect(content().json(message("ไม่พบ SKU ในรายการโอน"), true));
    send(owner, c.hq(), other.bn(), List.of(line(p, 1, List.of())))
        .andExpect(content().json(message("กรุณาเลือกสาขาปลายทาง"), true));
  }
}
