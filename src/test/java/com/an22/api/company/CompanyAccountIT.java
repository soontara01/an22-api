package com.an22.api.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
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

/** ขั้น 11: audit log, แพ็กเกจ, onboarding, export, ขอลบข้อมูล — สิทธิ์ / ข้อความตาม mock, ข้าม tenant. */
class CompanyAccountIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired TenantB tenantB;

  private Company c;
  private String owner;
  private String boss;
  private String staff;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
  }

  private void event(long tenant, Long branch, String action, String detail) {
    sql.platform(jdbc -> jdbc.sql("INSERT INTO dt_audit_log (tenant_id, actor_kind, user_name, branch_id, action, detail)"
            + " VALUES (:t, 'system', '', :b, :a, :d)")
        .param("t", tenant).param("b", branch).param("a", action).param("d", detail).update());
  }

  @Test
  void auditLogIsForAdminsAndBranchAdminsSeeOnlyTheirBranches() throws Exception {
    event(c.id(), c.hq(), "stock_out", "ของสาขา HQ");
    event(c.id(), c.bn(), "stock_out", "ของสาขา BN");
    event(c.id(), null, "menu_access", "ระดับบริษัท");
    event(tenantB.ids().tenantId(), null, "menu_access", "ของบริษัทบี");

    mvc.perform(lastYear(as(staff, get("/audit-log")))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูประวัติการใช้งานได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(owner, get("/audit-log"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"), true));
    mvc.perform(as(owner, get("/audit-log")).param("from", "2025-01-01").param("to", "2026-01-02"))
        .andExpect(content().json(message("ช่วงวันที่ต้องไม่เกิน 366 วัน"), true));

    // owner เห็นทุกอย่างของบริษัทตัวเอง (รวม login ของ setup) ใหม่สุดก่อน
    mvc.perform(lastYear(as(owner, get("/audit-log")))).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].detail").value("ระดับบริษัท"))
        .andExpect(jsonPath("$[*].detail", Matchers.hasItems("ของสาขา HQ", "ของสาขา BN", "ระดับบริษัท")))
        .andExpect(jsonPath("$[*].detail", Matchers.not(Matchers.hasItem("ของบริษัทบี"))))
        .andExpect(jsonPath("$[*].action", Matchers.hasItem("login")))
        .andExpect(jsonPath("$[0].ref").value(Matchers.nullValue()))
        .andExpect(jsonPath("$[0].actorKind").value("system"));
    // admin สาขา HQ: เฉพาะเหตุการณ์ของ HQ — ไม่เห็นระดับบริษัท / สาขาอื่น
    mvc.perform(lastYear(as(boss, get("/audit-log")))).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].detail", Matchers.contains("ของสาขา HQ")));
    // ตัวกรอง: action / branchId / userId; ค่าที่อ่านไม่ได้ = ไม่กรอง
    mvc.perform(lastYear(as(owner, get("/audit-log"))).param("action", "stock_out").param("branchId", Long.toString(c.bn())))
        .andExpect(jsonPath("$[*].detail", Matchers.contains("ของสาขา BN")));
    mvc.perform(lastYear(as(owner, get("/audit-log"))).param("action", "nope").param("userId", Long.toString(c.boss())))
        .andExpect(jsonPath("$[*].action", Matchers.contains("login")))
        .andExpect(jsonPath("$[0].userName").value("boss"));
    mvc.perform(lastYear(as(owner, get("/audit-log"))).param("userId", "x").param("action", "menu_access"))
        .andExpect(jsonPath("$[*].detail", Matchers.contains("ระดับบริษัท")));
    String today = LocalDate.now(ClockConfig.BANGKOK).toString();
    mvc.perform(as(owner, get("/audit-log")).param("from", "2020-01-01").param("to", "2020-12-31"))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(owner, get("/audit-log")).param("from", today).param("to", today))
        .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(3)));
  }

  @Test
  void subscriptionPlanAndOnboarding() throws Exception {
    mvc.perform(as(staff, get("/company/subscription"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูแพ็กเกจได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, get("/company/subscription"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").value("pro")).andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.usage.branches").value(2)).andExpect(jsonPath("$.usage.users").value(3))
        .andExpect(jsonPath("$.usage.terminals").value(0)).andExpect(jsonPath("$.subscription.nextInvoiceOn").value("9999-12-31"))
        .andExpect(jsonPath("$.subscription.trialEndsOn").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.invoices.length()").value(0));

    mvc.perform(as(boss, put("/company/plan"), Map.of("planId", "starter"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("เปลี่ยนแพ็กเกจได้เฉพาะเจ้าของบริษัท"), true));
    mvc.perform(as(owner, put("/company/plan"), Map.of("planId", "gold"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ไม่มีแพ็กเกจนี้"), true));
    // ลดแพ็กเกจได้แม้ใช้เกิน (2 สาขา > starter 1) — แค่เพิ่มต่อไม่ได้
    mvc.perform(as(owner, put("/company/plan"), Map.of("planId", "starter"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").value("starter")).andExpect(jsonPath("$.usage.branches").value(2));
    assertThat(companies.audit(c, "plan_change")).containsExactly("plan_change|" + c.code() + "|เปลี่ยนเป็นแพ็กเกจ Starter (฿590 / เดือน)");
    Map<String, Object> branch = Map.of("code", "B3", "name", "สาม", "branchNo", "00003", "address", "x", "phone", "",
        "receiptFooter", "", "active", true);
    mvc.perform(as(owner, post("/branches"), branch)).andExpect(status().isForbidden());

    mvc.perform(as(staff, get("/company/onboarding"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดูขั้นตอนเริ่มต้นใช้งานได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, get("/company/onboarding"))).andExpect(status().isOk())
        .andExpect(content().json("{\"terminals\":0,\"categories\":0,\"products\":0,\"prices\":0,\"users\":4}", true));
  }

  @Test
  void exportHasEveryCollectionDecryptedWithoutSecrets() throws Exception {
    mvc.perform(as(owner, post("/pos-terminals"), Map.of("id", 1, "name", "เครื่อง 1", "posId", "", "branchId", c.hq(), "active", true)))
        .andExpect(status().isOk());
    Map<String, Object> bill = new HashMap<>();
    bill.put("heldAt", "2026-10-23T03:00:00Z");
    bill.put("customer", "นายลับเฉพาะ");
    bill.put("items", List.of(Map.of("productId", 1, "factor", 1, "qty", 2, "serial", "")));
    bill.put("freeSerials", List.of());
    bill.put("buyer", Map.of("name", "คุณลูกค้า", "taxId", "1234567890123", "address", "กรุงเทพ"));
    bill.put("billManual", null);
    bill.put("manualReason", "");
    bill.put("total", 120.5);
    mvc.perform(as(staff, post("/pos/held-bills"), bill).header("X-Terminal-Id", 1).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk());

    mvc.perform(as(boss, get("/company/export"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ดาวน์โหลดข้อมูลทั้งบริษัทได้เฉพาะเจ้าของบริษัท"), true));
    String body = mvc.perform(as(owner, get("/company/export"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.company.id").value(c.id()))
        .andExpect(jsonPath("$.company.code").value(c.code())).andExpect(jsonPath("$.company.name").value("บริษัท " + c.code()))
        .andExpect(jsonPath("$.data.users[*].username", Matchers.contains("owner", "boss", "staff", "gone")))
        .andExpect(jsonPath("$.data.users[0].allBranches").value(true))
        .andExpect(jsonPath("$.data.users[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$.data.users[0].tenantId").doesNotExist())
        .andExpect(jsonPath("$.data.branches.length()").value(3))
        .andExpect(jsonPath("$.data.companyInfo[0].name").value("บริษัท " + c.code()))
        .andExpect(jsonPath("$.data.heldBills[0].payload.customer").value("นายลับเฉพาะ"))
        .andExpect(jsonPath("$.data.posTerminals[0].name").value("เครื่อง 1"))
        .andExpect(jsonPath("$.data.billingInvoices.length()").value(0))
        .andExpect(jsonPath("$.data.auditLog[*].action", Matchers.hasItem("login")))
        .andExpect(jsonPath("$.data.docCounters").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("$2a$").doesNotContain("password").doesNotContain("Hash")
        .doesNotContain(TenantB.CODE).doesNotContain("บริษัท บี");
    assertThat(companies.audit(c, "company_export")).containsExactly("company_export|" + c.code() + "|ดาวน์โหลดข้อมูลทั้งบริษัท (JSON)");
  }

  @Test
  void deletionSuspendsEveryoneButTheOwnerUntilCancelled() throws Exception {
    mvc.perform(as(boss, get("/company/deletion"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("ขอลบข้อมูลบริษัทได้เฉพาะเจ้าของบริษัท"), true));
    mvc.perform(as(owner, get("/company/deletion"))).andExpect(status().isOk()).andExpect(content().string(""));
    mvc.perform(as(owner, delete("/company/deletion"))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ไม่มีคำขอลบข้อมูล"), true));
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", "NOPE", "password", "1234", "reason", "")))
        .andExpect(content().json(message("พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน"), true));
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", c.code().toLowerCase(), "password", "", "reason", "")))
        .andExpect(content().json(message("กรุณากรอกรหัสผ่านเพื่อยืนยัน"), true));
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", c.code(), "password", "9999", "reason", "")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านไม่ถูกต้อง"), true));

    String purge = LocalDate.now(ClockConfig.BANGKOK).plusDays(30).toString();
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", c.code(), "password", "1234", "reason", " ปิดกิจการ ")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.purgeAfter").value(purge))
        .andExpect(jsonPath("$.reason").value("ปิดกิจการ")).andExpect(jsonPath("$.requestedBy").value("owner"));
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", c.code(), "password", "1234", "reason", "")))
        .andExpect(content().json(message("ขอลบข้อมูลไว้แล้ว"), true));
    assertThat(companies.audit(c, "deletion_request")).containsExactly("deletion_request|" + c.code() + "|ลบได้ตั้งแต่ " + purge + " — ปิดกิจการ");

    // ระหว่างรอลบ: คนอื่นหลุด, owner ใช้ได้แค่ company/* + audit-log
    mvc.perform(as(boss, get("/me"))).andExpect(status().isUnauthorized());
    mvc.perform(as(owner, get("/me"))).andExpect(status().isOk()).andExpect(jsonPath("$.company.deletionPurgeAfter").value(purge));
    mvc.perform(as(owner, get("/products"))).andExpect(status().isForbidden());
    mvc.perform(lastYear(as(owner, get("/audit-log")))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/company/export"))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/company/deletion"))).andExpect(jsonPath("$.purgeAfter").value(purge));

    mvc.perform(as(owner, delete("/company/deletion"))).andExpect(status().isOk());
    mvc.perform(as(owner, get("/company/deletion"))).andExpect(content().string(""));
    mvc.perform(as(boss, get("/me"))).andExpect(status().isOk());
    assertThat(companies.audit(c, "deletion_cancel")).containsExactly("deletion_cancel|" + c.code() + "|");
  }

  @Test
  void ownerEndpointsOfAnotherCompanyAreNotReachable() throws Exception {
    // tenant มาจาก token เท่านั้น: export / ลบของบริษัท A ไม่แตะ B
    String body = mvc.perform(as(owner, get("/company/export"))).andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("Admin B");
    mvc.perform(as(owner, post("/company/deletion"), Map.of("companyCode", TenantB.CODE, "password", "1234", "reason", "")))
        .andExpect(content().json(message("พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน"), true));
    Boolean bActive = sql.platform(jdbc -> jdbc.sql("SELECT active FROM dt_companies WHERE id = :id")
        .param("id", tenantB.ids().tenantId()).query(Boolean.class).single());
    assertThat(bActive).isTrue();
  }
}
