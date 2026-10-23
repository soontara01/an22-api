package com.an22.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.security.JwtService;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantB;
import com.an22.api.support.TenantSql;
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
import org.springframework.http.MediaType;

/** {@code /platform/companies/*}, {@code /platform/invoices/:id/pay} (E36 / E39) — ข้อความตาม mock, audit {@code platform}. */
class PlatformCompanyIT extends ApiTest {

  private static final String PLATFORM_NAME = "ผู้ดูแลแพลตฟอร์ม";

  @Autowired JwtService jwt;
  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired TenantB tenantB;

  private String platform;
  private Company c;

  @BeforeEach
  void setUp() {
    platform = jwt.issuePlatform(1);
    c = companies.create();
  }

  private List<String> platformAudit(String action) {
    return sql.platform(jdbc -> jdbc.sql("SELECT actor_kind || '|' || coalesce(user_id::text, '-') || '|' || user_name || '|'"
            + " || coalesce(ref_label, '') || '|' || detail FROM dt_audit_log WHERE tenant_id = :t AND action = :a ORDER BY id")
        .param("t", c.id()).param("a", action).query(String.class).list());
  }

  @Test
  void listsEveryCompanyOnlyForAPlatformToken() throws Exception {
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, get("/platform/companies"))).andExpect(status().isUnauthorized());
    mvc.perform(get("/platform/companies")).andExpect(status().isUnauthorized());
    tenantB.ids();
    mvc.perform(as(platform, get("/platform/companies"))).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", Matchers.hasItems("DEMO", TenantB.CODE, c.code())))
        .andExpect(jsonPath("$[?(@.code == '" + c.code() + "')].usage.branches", Matchers.contains(2)))
        .andExpect(jsonPath("$[?(@.code == '" + c.code() + "')].usage.users", Matchers.contains(3)))
        .andExpect(jsonPath("$[?(@.code == '" + c.code() + "')].openAmount", Matchers.contains(0)))
        .andExpect(jsonPath("$[?(@.code == '" + c.code() + "')].name", Matchers.contains("บริษัท " + c.code())))
        .andExpect(jsonPath("$[?(@.code == '" + c.code() + "')].deletedAt", Matchers.contains(Matchers.nullValue())));
    mvc.perform(as(platform, get("/platform/companies/" + c.id()))).andExpect(status().isOk())
        .andExpect(jsonPath("$.owner.username").value("owner")).andExpect(jsonPath("$.owner.email").value("owner@" + c.code().toLowerCase() + ".test"))
        .andExpect(jsonPath("$.suspendNote").value("")).andExpect(jsonPath("$.invoices.length()").value(0))
        .andExpect(jsonPath("$.status").value("active")).andExpect(jsonPath("$.planId").value("pro"));
    mvc.perform(as(platform, get("/platform/companies/999999"))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบบริษัท"), true));
    mvc.perform(as(platform, put("/platform/companies/999999/plan"), Map.of("planId", "pro"))).andExpect(status().isNotFound());
  }

  @Test
  void changesPlanSuspendsAndReactivatesWithAudit() throws Exception {
    mvc.perform(as(platform, put("/platform/companies/" + c.id() + "/plan"), Map.of("planId", "x")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่มีแพ็กเกจนี้"), true));
    mvc.perform(as(platform, put("/platform/companies/" + c.id() + "/plan"), Map.of("planId", "standard")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value("standard"));
    assertThat(platformAudit("plan_change")).containsExactly("platform|-|" + PLATFORM_NAME + "|" + c.code() + "|ผู้ให้บริการเปลี่ยนเป็นแพ็กเกจ Standard");

    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/suspend"), Map.of("note", " ")))
        .andExpect(content().json(message("กรุณากรอกเหตุผลที่ระงับ"), true));
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/suspend"), Map.of("note", " ผิดเงื่อนไข ")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.suspendedReason").value("platform")).andExpect(jsonPath("$.suspendNote").value("ผิดเงื่อนไข"));
    mvc.perform(post("/auth/login").header("X-Real-IP", "198.18.1.1").contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("companyCode", c.code(), "username", "staff", "password", "1234"))))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json(message("บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ"), true));
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/suspend"), Map.of("note", "อีก")))
        .andExpect(content().json(message("บริษัทนี้ถูกระงับอยู่แล้ว"), true));
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/reactivate"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.suspendedReason").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.suspendNote").value(""));
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/reactivate")))
        .andExpect(content().json(message("บริษัทนี้ใช้งานอยู่แล้ว"), true));
    companies.suspend(c, "overdue");
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/reactivate")))
        .andExpect(content().json(message("บริษัทค้างชำระ — บันทึกรับชำระใบแจ้งหนี้แทน"), true));
    assertThat(platformAudit("company_suspend")).containsExactly("platform|-|" + PLATFORM_NAME + "|" + c.code() + "|ผิดเงื่อนไข");
    assertThat(platformAudit("company_reactivate")).hasSize(1);
  }

  @Test
  void setsTheOwnersPassword() throws Exception {
    mvc.perform(as(platform, put("/platform/companies/" + c.id() + "/owner-password"), Map.of("password", "12")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านต้องมีอย่างน้อย 4 ตัว"), true));
    mvc.perform(as(platform, put("/platform/companies/" + c.id() + "/owner-password"), Map.of("password", "fresh1")))
        .andExpect(status().isOk()).andExpect(content().string(""));
    assertThat(login(c.code(), "owner", "fresh1")).isNotEmpty();
    assertThat(platformAudit("user_password"))
        .containsExactly("platform|-|" + PLATFORM_NAME + "|owner|ผู้ให้บริการตั้งรหัสผ่านใหม่ให้เจ้าของบริษัท");
  }

  @Test
  void payingTheOverdueInvoiceTurnsTheCompanyBackOn() throws Exception {
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    String no = String.format("SUB-1999%02d-%04d", c.id() / 10000 % 12 + 1, c.id() % 10000);
    long invoice = sql.platform(jdbc -> {
      jdbc.sql("UPDATE dt_companies SET next_invoice_on = :next, active = false, suspended_reason = 'overdue' WHERE id = :id")
          .param("next", today.plusDays(10)).param("id", c.id()).update();
      return jdbc.sql("INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on,"
              + " due_date, status) VALUES (:c, :no, 'starter', :start, :end, 590, :start, :due, 'open') RETURNING id")
          .param("c", c.id()).param("no", no).param("start", today.minusDays(40)).param("end", today.minusDays(11))
          .param("due", today.minusDays(33)).query(Long.class).single();
    });
    mvc.perform(as(platform, get("/platform/companies/" + c.id()))).andExpect(jsonPath("$.status").value("suspended"))
        .andExpect(jsonPath("$.openAmount").value(590.0));
    mvc.perform(as(platform, post("/platform/invoices/999999999/pay"))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบใบแจ้งหนี้"), true));
    mvc.perform(as(platform, post("/platform/invoices/" + invoice + "/pay"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.suspendedReason").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.invoices[0].status").value("paid")).andExpect(jsonPath("$.invoices[0].paidAt").isString())
        .andExpect(jsonPath("$.openAmount").value(0));
    mvc.perform(as(platform, post("/platform/invoices/" + invoice + "/pay")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ใบแจ้งหนี้นี้ชำระแล้ว"), true));
    assertThat(platformAudit("invoice_paid")).containsExactly("platform|-|" + PLATFORM_NAME + "|" + no + "|฿590");
  }

  @Test
  void purgeDeletesEveryTableOfTheCompanyAfterTheWait() throws Exception {
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", 1, "name", "เครื่อง 1", "posId", "", "branchId", c.hq(), "active", true)))
        .andExpect(status().isOk());
    Map<String, Object> bill = new HashMap<>();
    bill.put("heldAt", "2026-10-23T03:00:00Z");
    bill.put("customer", "ลูกค้า");
    bill.put("items", List.of(Map.of("productId", 1, "factor", 1, "qty", 1, "serial", "")));
    bill.put("freeSerials", List.of());
    bill.put("buyer", null);
    bill.put("billManual", null);
    bill.put("manualReason", "");
    bill.put("total", 10);
    mvc.perform(as(staff, post("/pos/held-bills"), bill).header("X-Terminal-Id", 1).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk());
    mvc.perform(post("/auth/forgot-password").header("X-Real-IP", "198.18.2.1").contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("companyCode", c.code(), "email", "owner@" + c.code().toLowerCase() + ".test"))));

    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/purge"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("บริษัทนี้ไม่ได้ขอลบข้อมูล"), true));
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", c.code(), "password", "1234", "reason", "")))
        .andExpect(status().isOk());
    LocalDate purgeAfter = LocalDate.now(ClockConfig.BANGKOK).plusDays(30);
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/purge"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ลบข้อมูลได้ตั้งแต่วันที่ " + purgeAfter.getDayOfMonth() + "/" + purgeAfter.getMonthValue() + "/"
            + (purgeAfter.getYear() + 543)), true));
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/reactivate")))
        .andExpect(content().json(message("บริษัทอยู่ระหว่างรอลบข้อมูล — ให้เจ้าของบริษัทยกเลิกคำขอลบ"), true));

    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET purge_after = current_date WHERE id = :id").param("id", c.id()).update());
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/purge"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedAt").isString()).andExpect(jsonPath("$.name").value("บริษัท " + c.code()))
        .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.owner").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.usage.users").value(0)).andExpect(jsonPath("$.deletion.purgeAfter").isString());

    // ไม่เหลือแถวของบริษัทในตารางใดเลย (ยกเว้นทะเบียน + ใบแจ้งหนี้)
    List<String> tables = sql.platform(jdbc -> jdbc.sql("SELECT DISTINCT table_name FROM information_schema.columns"
        + " WHERE table_schema = 'public' AND column_name = 'tenant_id'").query(String.class).list());
    for (String table : tables) {
      long left = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", c.id())
          .query(Long.class).single());
      assertThat(left).as(table).isZero();
    }
    long tokens = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = :t").param("t", c.id())
        .query(Long.class).single());
    assertThat(tokens).isZero();
    long otherUsers = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM dt_users WHERE tenant_id = :t")
        .param("t", tenantB.ids().tenantId()).query(Long.class).single());
    assertThat(otherUsers).isPositive();

    // login / token เดิม = ไม่มีบริษัท; ทำอะไรต่อไม่ได้; รหัสห้ามสมัครซ้ำ
    mvc.perform(post("/auth/login").header("X-Real-IP", "198.18.2.2").contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("companyCode", c.code(), "username", "owner", "password", "1234"))))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json(message("รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง"), true));
    mvc.perform(as(owner, get("/me"))).andExpect(status().isUnauthorized());
    mvc.perform(as(platform, post("/platform/companies/" + c.id() + "/purge"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("บริษัทนี้ถูกลบข้อมูลแล้ว"), true));
    mvc.perform(as(platform, get("/platform/companies/" + c.id()))).andExpect(status().isOk());
  }
}
