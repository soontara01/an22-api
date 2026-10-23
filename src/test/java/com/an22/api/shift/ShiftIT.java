package com.an22.api.shift;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /shifts/*} — เปิดกะ (เครื่อง / คน), เงินเข้าออก + blind count (B19 / S2), นับ + ปิด + อนุมัติ (S3), ปิดแทน, ปรับปรุง (S8), ล็อกหน้าจอ (E48),
 * P6, ปิดสาขา / เครื่องที่มีกะเปิด, ข้าม tenant, พร้อมกัน.
 */
class ShiftIT extends ApiTest {

  @Autowired TestCompanies companies;

  private Company c;
  private String owner;
  private String boss;
  private String staff;
  private long cash;
  private long transfer;
  private final String day = LocalDate.now(ClockConfig.BANGKOK).format(DateTimeFormatter.BASIC_ISO_DATE);
  /** IP ของ test นี้ (ตัวนับรหัสผิดต่อ IP อยู่ใน DB ที่ทุก test ใช้ร่วม). */
  private final String ip = "10.8." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    for (int id : new int[] {1, 2}) {
      mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", id, "name", "เครื่อง " + id, "posId", "", "branchId", c.hq(),
          "active", true))).andExpect(status().isOk());
    }
    cash = id(mvc.perform(as(owner, post("/payment-methods"), method("CASH", "cash"))));
    transfer = id(mvc.perform(as(owner, post("/payment-methods"), method("TR", "transfer"))));
  }

  private static Map<String, Object> method(String code, String type) {
    return Map.of("code", code, "name", code.equals("CASH") ? "เงินสด" : "โอน", "type", type, "active", true);
  }

  private long id(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
  }

  /** request ที่เครื่อง {@code terminal} สาขา HQ. */
  private MockHttpServletRequestBuilder at(int terminal, MockHttpServletRequestBuilder request) {
    return request.header("X-Terminal-Id", terminal).header("X-Branch-Id", c.hq()).header("X-Real-IP", ip);
  }

  private ResultActions open(String token, int terminal, double openingFloat) throws Exception {
    return mvc.perform(at(terminal, as(token, post("/shifts/open"), Map.of("openingFloat", openingFloat))));
  }

  private static Map<String, Object> count(long methodId, Object counted, boolean confirmed, String reasonCode) {
    Map<String, Object> m = new HashMap<>();
    m.put("methodId", methodId);
    m.put("counted", counted);
    m.put("firstCount", null);
    m.put("confirmed", confirmed);
    m.put("reasonCode", reasonCode);
    m.put("reason", "");
    return m;
  }

  private Map<String, Object> closeBody(double cashCounted, String reasonCode) {
    Map<String, Object> m = new HashMap<>();
    m.put("counts", List.of(count(cash, cashCounted, false, reasonCode), count(transfer, null, true, null)));
    m.put("cashDenominations", List.of());
    m.put("depositAmount", 0);
    m.put("note", "");
    m.put("approval", null);
    return m;
  }

  @Test
  void opensOneShiftPerTerminalAndPerCashier() throws Exception {
    mvc.perform(as(staff, post("/shifts/open"), Map.of("openingFloat", 1000)).header("X-Branch-Id", c.hq()))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า"), true));
    mvc.perform(at(1, as(staff, post("/shifts/open"), Map.of("terminalId", 2, "openingFloat", 1000))))
        .andExpect(content().json(message("เลขเครื่องไม่ตรงกับเครื่องนี้ (เครื่อง 1)"), true));
    open(staff, 1, -1).andExpect(content().json(message("เงินทอนตั้งต้นต้องไม่ติดลบ"), true));
    open(staff, 1, 1000).andExpect(status().isOk())
        .andExpect(jsonPath("$.shiftNo").value("SH-HQ-" + day + "-01")).andExpect(jsonPath("$.cashier").value("staff"))
        .andExpect(jsonPath("$.status").value("open")).andExpect(jsonPath("$.branchId").value((int) c.hq()))
        .andExpect(jsonPath("$.lockedAt").value((Object) null)).andExpect(jsonPath("$.adjustmentTotal").doesNotExist());
    mvc.perform(at(1, as(staff, get("/shifts/current")))).andExpect(jsonPath("$.cashier").value("staff"));
    // ไม่มีกะ = body ว่าง (HttpClient ของหน้าบ้านอ่านเป็น null)
    mvc.perform(at(2, as(staff, get("/shifts/current")))).andExpect(status().isOk()).andExpect(content().string(""));
    open(boss, 1, 0).andExpect(content().json(message("เครื่องนี้มีกะที่เปิดอยู่แล้ว"), true));
    open(staff, 2, 0).andExpect(content().json(message("คุณมีกะที่เปิดอยู่ที่เครื่องอื่น — ปิดกะนั้นก่อน"), true));
    open(boss, 2, 500).andExpect(jsonPath("$.shiftNo").value("SH-HQ-" + day + "-02"));
  }

  @Test
  void blindCountCloseApproveAndAdjust() throws Exception {
    long shift = id(open(staff, 1, 1000).andExpect(status().isOk()));
    // เงินเข้าออก: พนักงานไม่รู้ยอดในลิ้นชัก (B19)
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/cash-movements"), Map.of("type", "out", "amount", 5000, "reason", "ค่าส่ง"))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เงินสดในลิ้นชักไม่พอ"), true));
    mvc.perform(at(1, as(owner, post("/shifts/" + shift + "/cash-movements"), Map.of("type", "out", "amount", 5000, "reason", "ค่าส่ง"))))
        .andExpect(content().json(message("เงินสดในลิ้นชักไม่พอ (คาดว่ามี 1,000 บาท)"), true));
    mvc.perform(at(2, as(staff, post("/shifts/" + shift + "/cash-movements"), Map.of("type", "in", "amount", 200, "reason", "แลกเหรียญ"))))
        .andExpect(content().json(message("กะนี้ไม่ได้เปิดที่เครื่องนี้"), true));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/cash-movements"), Map.of("type", "in", "amount", 200, "reason", " แลกเหรียญ "))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("แลกเหรียญ")).andExpect(jsonPath("$.by").value("staff"));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/drawer-opens"), Map.of("reason", " "))))
        .andExpect(content().json(message("กรุณาระบุเหตุผลที่เปิดลิ้นชัก"), true));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/drawer-opens"), Map.of("reason", "แลกเหรียญ / แลกแบงก์"))))
        .andExpect(status().isOk());

    // blind count: พนักงานไม่เห็นยอดต่อช่องทางระหว่างกะเปิด, admin เห็น
    mvc.perform(as(staff, get("/shifts/" + shift + "/summary")))
        .andExpect(jsonPath("$.lines").doesNotExist()).andExpect(jsonPath("$.cashIn").value(200.0))
        .andExpect(jsonPath("$.drawerOpenCount").value(1))
        .andExpect(jsonPath("$.checklist[*].closeCheck", Matchers.contains("count", "confirm")));
    mvc.perform(as(owner, get("/shifts/" + shift + "/summary"))).andExpect(jsonPath("$.lines[0].expected").value(1200.0));

    mvc.perform(as(staff, post("/shifts/" + shift + "/count-check"), Map.of("counts", List.of(count(cash, 1000, false, null)))))
        .andExpect(content().json(message("กรุณาตรวจยอด โอน แล้วติ๊ก \"ตรวจแล้ว\""), true));
    mvc.perform(as(staff, post("/shifts/" + shift + "/count-check"), closeBody(1000, null)))
        .andExpect(jsonPath("$.lines[0].variance").value(-200.0)).andExpect(jsonPath("$.needsApproval").value(true));

    // ปิด: ขาด 200 (> เพดาน 100) ต้องมีสาเหตุ, ไม่มีผู้อนุมัติ = รออนุมัติ
    mvc.perform(as(staff, post("/shifts/" + shift + "/close"), closeBody(1000, null)))
        .andExpect(content().json(message("เงินสด ขาด 200 บาท — กรุณาระบุสาเหตุ"), true));
    Map<String, Object> wrongApprover = closeBody(1000, "wrong_change");
    wrongApprover.put("approval", Map.of("username", "boss", "password", "ผิด"));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/close"), wrongApprover)))
        .andExpect(content().json(message("รหัสผ่านผู้อนุมัติไม่ถูกต้อง"), true));
    mvc.perform(as(staff, post("/shifts/" + shift + "/close"), closeBody(1000, "wrong_change")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("closed"))
        .andExpect(jsonPath("$.approvalStatus").value("pending")).andExpect(jsonPath("$.floatLeft").value(1000.0))
        .andExpect(jsonPath("$.counts[1].confirmedBy").value("staff"));
    mvc.perform(at(1, as(staff, get("/shifts/last-float")))).andExpect(jsonPath("$.floatLeft").value(1000.0));

    mvc.perform(as(staff, post("/shifts/" + shift + "/approve"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("อนุมัติส่วนต่างได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, post("/shifts/" + shift + "/approve"))).andExpect(jsonPath("$.approvalStatus").value("approved"))
        .andExpect(jsonPath("$.approvedBy").value("boss"));
    mvc.perform(as(boss, post("/shifts/" + shift + "/approve"))).andExpect(content().json(message("กะนี้ไม่ได้รออนุมัติ"), true));

    // ปรับปรุงหลังปิด (S8): บันทึกแยก ยอดนับเดิมไม่เปลี่ยน
    mvc.perform(as(staff, post("/shifts/" + shift + "/adjustments"), Map.of("methodId", cash, "amount", 150, "reasonCode", "found_cash")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ปรับปรุงกะได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(owner, post("/shifts/" + shift + "/adjustments"), Map.of("methodId", transfer, "amount", 1, "reasonCode", "other")))
        .andExpect(content().json(message("ปรับปรุงได้เฉพาะช่องทางที่นับตอนปิดกะ"), true));
    mvc.perform(as(owner, post("/shifts/" + shift + "/adjustments"),
            Map.of("methodId", cash, "amount", 150, "reasonCode", "found_cash", "reason", "ใต้ลิ้นชัก")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("เงินสด"));
    mvc.perform(as(owner, get("/shifts/" + shift))).andExpect(jsonPath("$.adjustmentTotal").value(150.0))
        .andExpect(jsonPath("$.counts[0].counted").value(1000.0));
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    mvc.perform(as(owner, get("/shifts/variance-report").param("from", today.toString()).param("to", today.toString()))
            .header("X-Branch-Id", c.hq()))
        .andExpect(jsonPath("$[0].cashier").value("staff")).andExpect(jsonPath("$[0].shortTotal").value(50.0))
        .andExpect(jsonPath("$[0].drawerOpens").value(1));
    assertThat(companies.audit(c, "shift_approve")).containsExactly("shift_approve|SH-HQ-" + day + "-01|กะของ staff");
    assertThat(companies.audit(c, "shift_adjust")).containsExactly("shift_adjust|SH-HQ-" + day + "-01|+150 — ใต้ลิ้นชัก");
  }

  @Test
  void adminClosesOnBehalfAndOnlyOwnShiftsAreVisibleToStaff() throws Exception {
    companies.setUserActive(c, c.gone(), true);
    String other = login(c.code(), "gone", TestCompanies.PASSWORD);
    long shift = id(open(staff, 1, 0).andExpect(status().isOk()));
    mvc.perform(as(other, get("/shifts/" + shift))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูได้เฉพาะกะของตัวเอง"), true));
    mvc.perform(as(other, post("/shifts/" + shift + "/close"), closeBody(0, null))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ปิดกะได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ"), true));
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    mvc.perform(as(other, lastYear(get("/shifts"))).header("X-Branch-Id", c.hq())).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(staff, lastYear(get("/shifts"))).header("X-Branch-Id", c.hq())).andExpect(jsonPath("$[0].id").value((int) shift));
    mvc.perform(as(staff, get("/shifts")).header("X-Branch-Id", c.hq())).andExpect(status().isBadRequest())
        .andExpect(content().json(message("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"), true));
    mvc.perform(as(staff, get("/shifts").param("from", today.toString()).param("to", today.toString()).param("branchId", "all")))
        .andExpect(status().isForbidden());

    mvc.perform(as(boss, post("/shifts/" + shift + "/close"), closeBody(0, null))).andExpect(jsonPath("$.closedBy").value("boss"))
        .andExpect(jsonPath("$.approvalStatus").value("not_needed"));
    assertThat(companies.audit(c, "shift_close_other")).containsExactly("shift_close_other|SH-HQ-" + day + "-01|กะของ staff");
    mvc.perform(as(boss, post("/shifts/" + shift + "/close"), closeBody(0, null))).andExpect(content().json(message("กะนี้ปิดไปแล้ว"), true));
  }

  @Test
  void theScreenLockIsTheShiftsAndUnlocksWithTheCashierOrAnAdmin() throws Exception {
    companies.setUserActive(c, c.gone(), true);
    long shift = id(open(staff, 1, 0).andExpect(status().isOk()));
    mvc.perform(at(2, as(staff, post("/shifts/" + shift + "/lock")))).andExpect(content().json(message("กะนี้ไม่ได้เปิดที่เครื่องนี้"), true));
    String lockedAt = JSON.readTree(mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/lock")))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString()).path("lockedAt").asString();
    assertThat(lockedAt).isNotBlank();
    // ล็อกซ้ำไม่เปลี่ยนเวลา
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/lock")))).andExpect(jsonPath("$.lockedAt").value(lockedAt));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/unlock"), Map.of("username", "staff", "password", "ผิด"))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง"), true));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/unlock"), Map.of("username", "gone", "password", TestCompanies.PASSWORD))))
        .andExpect(content().json(message("ปลดล็อกได้เฉพาะ staff หรือผู้ดูแลระบบ"), true));
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/unlock"), Map.of("username", "BOSS", "password", TestCompanies.PASSWORD))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.lockedAt").value((Object) null));
    // ปิดกะ = ปลดล็อก
    mvc.perform(at(1, as(staff, post("/shifts/" + shift + "/lock"))));
    mvc.perform(as(staff, post("/shifts/" + shift + "/close"), closeBody(0, null))).andExpect(jsonPath("$.lockedAt").value((Object) null));
  }

  @Test
  void openShiftsKeepTheirTerminalBranchAndShiftSystemOn() throws Exception {
    open(staff, 1, 0).andExpect(status().isOk());
    mvc.perform(as(owner, put("/pos-terminals/1"), Map.of("active", false)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เครื่อง 1 มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้"), true));
    mvc.perform(as(owner, put("/branches/" + c.hq()), Map.of("active", false)))
        .andExpect(content().json(message("สาขา HQ มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้"), true));
    mvc.perform(as(owner, delete("/pos-terminals/1")))
        .andExpect(content().json(message("เครื่อง 1 มีกะหรือเอกสารแล้ว ลบไม่ได้ (ปิดใช้งานแทน)"), true));
    Map<String, Object> settings = new HashMap<>();
    settings.put("name", "ร้าน");
    settings.put("taxId", "");
    settings.put("vatRegistered", false);
    settings.put("taxInvoicePaper", "A4");
    settings.put("exchangeDays", 7);
    settings.put("manualDiscountMaxPercent", 5);
    settings.put("requireShift", false);
    settings.put("shiftVarianceLimit", 100);
    settings.put("shiftMaxHours", 12);
    settings.put("staffSeeCost", false);
    mvc.perform(as(owner, put("/settings/company"), settings)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ยังมีกะที่เปิดอยู่ 1 กะ — ปิดกะทั้งหมดก่อนปิดการใช้ระบบกะ"), true));
  }

  @Test
  void concurrentOpensOnOneTerminalMakeOneShift() throws Exception {
    List<String> tokens = List.of(owner, boss, staff);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (String token : tokens) {
        Callable<Integer> call = () -> open(token, 1, 0).andReturn().getResponse().getStatus();
        results.add(pool.submit(call));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : results) {
        statuses.add(f.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 400, 400);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void anotherCompanysShiftIsNotFound() throws Exception {
    long shift = id(open(staff, 1, 0).andExpect(status().isOk()));
    Company other = companies.create();
    String stranger = login(other.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(stranger, get("/shifts/" + shift))).andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบกะ"), true));
    mvc.perform(as(stranger, post("/shifts/" + shift + "/close"), closeBody(0, null))).andExpect(status().isNotFound());
    mvc.perform(as(stranger, post("/shifts/" + shift + "/approve"))).andExpect(status().isNotFound());
    mvc.perform(as(stranger, post("/shifts/" + shift + "/lock")).header("X-Terminal-Id", 1)).andExpect(status().isNotFound());
    mvc.perform(as(stranger, lastYear(get("/shifts"))).param("branchId", "all")).andExpect(jsonPath("$.length()").value(0));
    // เครื่อง 1 ของบริษัทนี้ไม่มีในทะเบียนของอีกบริษัท
    mvc.perform(as(stranger, post("/shifts/open"), Map.of("openingFloat", 0)).header("X-Terminal-Id", 1).header("X-Branch-Id", other.hq()))
        .andExpect(content().json(message("ไม่พบเครื่อง 1 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่"), true));
  }
}
