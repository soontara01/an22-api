package com.an22.api.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code /pos-terminals} — สิทธิ์ admin สาขา (E6), กฎ, แพ็กเกจ, audit + terminalId ของ audit. */
class PosTerminalIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  private Company c;
  private String owner;
  private String boss;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
  }

  private static Map<String, Object> terminal(int id, long branchId, String posId) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", id);
    m.put("name", " เครื่อง " + id + " ");
    m.put("posId", posId);
    m.put("branchId", branchId);
    m.put("active", true);
    return m;
  }

  @Test
  void demoHasThreeTerminals() throws Exception {
    String demo = login("DEMO", "somchai", "1234");
    mvc.perform(as(demo, get("/pos-terminals")))
        .andExpect(jsonPath("$[*].id", Matchers.contains(1, 2, 3)))
        .andExpect(jsonPath("$[2].branchId").value(2))
        .andExpect(jsonPath("$[0].posId").value("E051234567890"));
  }

  @Test
  void createUpdateDeleteWithAudit() throws Exception {
    mvc.perform(as(owner, post("/pos-terminals"), terminal(5, c.hq(), "P-5")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("เครื่อง 5"));
    mvc.perform(as(owner, post("/pos-terminals"), terminal(5, c.hq(), "")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("มีเครื่อง 5 อยู่แล้ว"), true));
    mvc.perform(as(owner, post("/pos-terminals"), terminal(6, c.hq(), "P-5")))
        .andExpect(content().json(message("POS ID P-5 ใช้กับเครื่องอื่นแล้ว"), true));
    mvc.perform(as(owner, post("/pos-terminals"), terminal(6, c.old(), "")))
        .andExpect(content().json(message("สาขานี้ปิดใช้งานแล้ว เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน"), true));
    // เลขเครื่องเปลี่ยนไม่ได้ (id ใน body ไม่สนใจ)
    mvc.perform(as(owner, put("/pos-terminals/5"), Map.of("id", 9, "branchId", c.bn(), "active", false)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(5)).andExpect(jsonPath("$.branchId").value(c.bn()));
    mvc.perform(as(owner, delete("/pos-terminals/5"))).andExpect(status().isOk());
    mvc.perform(as(owner, delete("/pos-terminals/5"))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบเครื่อง POS"), true));
    assertThat(companies.audit(c, "terminal_change")).containsExactly(
        "terminal_change|เครื่อง 5|เพิ่มเครื่อง เครื่อง 5 · POS ID P-5",
        "terminal_change|เครื่อง 5|แก้เครื่อง เครื่อง 5 · POS ID P-5 (ปิดใช้งาน)",
        "terminal_change|เครื่อง 5|ลบเครื่อง");
  }

  @Test
  void branchAdminsEditOnlyTheirBranchesTerminals() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(staff, post("/pos-terminals"), terminal(7, c.hq(), "")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ทะเบียนเครื่อง POS ได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, post("/pos-terminals"), terminal(7, c.bn(), "")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง"), true));
    mvc.perform(as(boss, post("/pos-terminals"), terminal(7, c.hq(), ""))).andExpect(status().isOk());
    // ย้ายไปสาขาที่ไม่มีสิทธิ์ไม่ได้
    mvc.perform(as(boss, put("/pos-terminals/7"), Map.of("branchId", c.bn()))).andExpect(status().isForbidden());
  }

  @Test
  void packageLimitsActiveTerminals() throws Exception {
    companies.setPlan(c, "starter");
    mvc.perform(as(owner, post("/pos-terminals"), terminal(1, c.hq(), ""))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/pos-terminals"), terminal(2, c.hq(), ""))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/pos-terminals"), terminal(3, c.hq(), "")))
        .andExpect(status().isForbidden())
        .andExpect(content().json(message("แพ็กเกจ Starter ใช้เครื่อง POS ที่ใช้งานได้ 2 เครื่อง — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"), true));
    Map<String, Object> off = terminal(3, c.hq(), "");
    off.put("active", false);
    mvc.perform(as(owner, post("/pos-terminals"), off)).andExpect(status().isOk());
    mvc.perform(as(owner, put("/pos-terminals/3"), Map.of("active", true))).andExpect(status().isForbidden());
  }

  @Test
  void auditRecordsTheDevicesTerminalOnlyWhenItIsTheCompanys() throws Exception {
    mvc.perform(as(owner, post("/pos-terminals"), terminal(4, c.hq(), ""))).andExpect(status().isOk());
    mvc.perform(as(owner, put("/settings/menu-access"), Map.of("userMenus", List.of("pos"))).header("X-Terminal-Id", "4"))
        .andExpect(status().isOk());
    mvc.perform(as(owner, put("/settings/menu-access"), Map.of("userMenus", List.of("sales"))).header("X-Terminal-Id", "99"))
        .andExpect(status().isOk());
    var terminals = sql.platform(jdbc -> jdbc.sql(
            "SELECT coalesce(terminal_id::text, '-') FROM dt_audit_log WHERE tenant_id = :t AND action = 'menu_access' ORDER BY id")
        .param("t", c.id()).query(String.class).list());
    assertThat(terminals).containsExactly("4", "-");
  }
}
