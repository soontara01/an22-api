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

  private static final String INVALID_LINK = "ลิงก์ตั้งรหัสผ่านไม่ถูกต้อง หมดอายุ หรือใช้ไปแล้ว — ขอลิงก์ใหม่";

  private static String newCode() {
    return "N" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static Map<String, Object> newCompany(String code) {
    Map<String, Object> p = new HashMap<>();
    p.put("companyCode", " " + code.toLowerCase() + " ");
    p.put("companyName", " บริษัท แอคมี จำกัด ");
    p.put("taxId", "0105550123451");
    p.put("vatRegistered", true);
    p.put("branch", Map.of("code", "hq", "name", "สำนักงานใหญ่", "branchNo", "00000", "address", "กรุงเทพฯ", "phone", ""));
    p.put("owner", Map.of("name", "สมศักดิ์", "username", "Boss", "email", "boss@acme.test"));
    p.put("planId", "starter");
    return p;
  }

  private static String token(String path) {
    return path.substring(path.indexOf("token=") + "token=".length());
  }

  private org.springframework.test.web.servlet.ResultActions reset(String token, String password) throws Exception {
    return mvc.perform(post("/auth/reset-password").header("X-Real-IP", "198.51.100.77").contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("token", token, "password", password))));
  }

  private int loginStatus(String code, String username, String password) throws Exception {
    return mvc.perform(post("/auth/login").header("X-Real-IP", "198.51.100.78").contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("companyCode", code, "username", username, "password", password))))
        .andReturn().getResponse().getStatus();
  }

  @Test
  void createsACompanyWhoseOwnerSetsTheirPasswordWithTheLink() throws Exception {
    String code = newCode();
    String trialEnd = LocalDate.now(ClockConfig.BANGKOK).plusDays(14).toString();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, post("/platform/companies"), newCompany(code))).andExpect(status().isUnauthorized());
    String body = mvc.perform(as(platform, post("/platform/companies"), newCompany(code))).andExpect(status().isOk())
        .andExpect(jsonPath("$.company.code").value(code)).andExpect(jsonPath("$.company.name").value("บริษัท แอคมี จำกัด"))
        .andExpect(jsonPath("$.company.status").value("trial")).andExpect(jsonPath("$.company.planId").value("starter"))
        .andExpect(jsonPath("$.company.subscription.trialEndsOn").value(trialEnd))
        .andExpect(jsonPath("$.company.owner.username").value("boss")).andExpect(jsonPath("$.company.ownerPasswordSet").value(false))
        .andExpect(jsonPath("$.company.invoices.length()").value(0))
        .andExpect(jsonPath("$.setupLink.path").value(Matchers.matchesPattern("^/auth/reset-password\\?token=[A-Za-z0-9_-]{43}$")))
        .andReturn().getResponse().getContentAsString();
    var created = JSON.readTree(body);
    long id = created.path("company").path("id").asLong();
    String link = token(created.path("setupLink").path("path").asString());
    // owner ไม่มีรหัสผ่าน: login ไม่ผ่านด้วยรหัสใดก็ตาม
    Boolean noPassword = sql.platform(jdbc -> jdbc.sql("SELECT password_hash IS NULL FROM dt_users WHERE tenant_id = :t AND role = 'owner'")
        .param("t", id).query(Boolean.class).single());
    assertThat(noPassword).isTrue();
    assertThat(loginStatus(code, "boss", "secret")).isEqualTo(401);
    // DB เก็บแค่ hash; ลิงก์ setup อายุ 72 ชม.
    List<String> links = sql.platform(jdbc -> jdbc.sql("SELECT kind || ':' || round(extract(epoch FROM expires_at - now()) / 3600)"
        + " FROM dt_password_reset_tokens WHERE company_id = :c").param("c", id).query(String.class).list());
    assertThat(links).containsExactly("setup:72");

    reset(link, "ab").andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านต้องมีอย่างน้อย 4 ตัว"), true));
    reset(link, "secret").andExpect(status().isOk());
    reset(link, "again").andExpect(status().isBadRequest()).andExpect(content().json(message(INVALID_LINK), true));
    String token = login(code, "boss", "secret");
    mvc.perform(as(token, get("/payment-methods"))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("CASH"));
    mvc.perform(as(token, get("/branches"))).andExpect(jsonPath("$[0].code").value("HQ"))
        .andExpect(jsonPath("$[0].receiptFooter").value("ขอบคุณที่ใช้บริการ"));
    mvc.perform(as(token, get("/company/onboarding")))
        .andExpect(content().json("{\"terminals\":0,\"categories\":0,\"products\":0,\"prices\":0,\"users\":1}", true));
    mvc.perform(as(platform, get("/platform/companies/" + id))).andExpect(jsonPath("$.ownerPasswordSet").value(true));
    List<String> events = sql.platform(jdbc -> jdbc.sql("SELECT action || '|' || actor_kind || '|' || user_name || '|' || detail"
        + " FROM dt_audit_log WHERE tenant_id = :t AND action IN ('company_create', 'password_set') ORDER BY id").param("t", id)
        .query(String.class).list());
    assertThat(events).containsExactly("company_create|platform|" + PLATFORM_NAME + "|แพ็กเกจ Starter ทดลองใช้ถึง " + trialEnd,
        "password_set|user|สมศักดิ์|ตั้งรหัสผ่านด้วยลิงก์จากผู้ให้บริการ");

    // รหัสเดิม (ตัวพิมพ์ต่าง) ใช้ไม่ได้อีก — รวมบริษัทที่ถูกลบแล้ว
    mvc.perform(as(platform, post("/platform/companies"), newCompany(code))).andExpect(status().isBadRequest())
        .andExpect(content().json(message("รหัสบริษัทนี้ถูกใช้แล้ว"), true));
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET deleted_at = now() WHERE id = :id").param("id", id).update());
    mvc.perform(as(platform, post("/platform/companies"), newCompany(code))).andExpect(content().json(message("รหัสบริษัทนี้ถูกใช้แล้ว"), true));
  }

  @Test
  void checksTheNewCompanyInOrder() throws Exception {
    Map<String, Object> p = newCompany(newCode());
    p.put("companyName", " ");
    mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("กรุณากรอกชื่อบริษัท"), true));
    p = newCompany("ab");
    mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(content().json(message("รหัสบริษัทใช้ได้เฉพาะ A-Z, 0-9 ยาว 3–20 ตัว"), true));
    p = newCompany(newCode());
    p.put("owner", Map.of("name", "ส", "username", "boss", "email", ""));
    mvc.perform(as(platform, post("/platform/companies"), p))
        .andExpect(content().json(message("เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)"), true));
    p = newCompany(newCode());
    p.put("planId", "gold");
    mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(content().json(message("กรุณาเลือกแพ็กเกจ"), true));
    for (Object days : List.of(91, -1, 1.5)) {
      p = newCompany(newCode());
      p.put("trialDays", days);
      mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(status().isBadRequest())
          .andExpect(content().json(message("วันทดลองใช้ต้องเป็นจำนวนเต็ม 0–90 วัน"), true));
    }
    p = newCompany(newCode());
    p.remove("branch");
    mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(status().isBadRequest());
  }

  @Test
  void billsAtOnceWithoutATrial() throws Exception {
    Map<String, Object> p = newCompany(newCode());
    p.put("trialDays", 0);
    String today = LocalDate.now(ClockConfig.BANGKOK).toString();
    mvc.perform(as(platform, post("/platform/companies"), p)).andExpect(status().isOk())
        .andExpect(jsonPath("$.company.subscription.trialEndsOn").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.company.invoices.length()").value(1))
        .andExpect(jsonPath("$.company.invoices[0].periodStart").value(today))
        .andExpect(jsonPath("$.company.invoices[0].status").value("open"));
  }

  @Test
  void aNewSetupLinkEndsTheOldOneAndOwnerPasswordIsGone() throws Exception {
    mvc.perform(as(platform, put("/platform/companies/" + c.id() + "/owner-password"), Map.of("password", "fresh1")))
        .andExpect(status().isNotFound());
    String url = "/platform/companies/" + c.id() + "/owner-setup-link";
    String first = JSON.readTree(mvc.perform(as(platform, post(url))).andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresAt").isString()).andReturn().getResponse().getContentAsString()).path("path").asString();
    String second = JSON.readTree(mvc.perform(as(platform, post(url))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString()).path("path").asString();
    reset(token(first), "fresh1").andExpect(status().isBadRequest()).andExpect(content().json(message(INVALID_LINK), true));
    reset(token(second), "fresh1").andExpect(status().isOk());
    assertThat(login(c.code(), "owner", "fresh1")).isNotEmpty();
    assertThat(platformAudit("owner_setup_link")).hasSize(2).allMatch(e -> e.startsWith("platform|-|" + PLATFORM_NAME + "|owner|ลิงก์ใช้ได้ถึง "));
    mvc.perform(as(platform, post("/platform/companies/999999/owner-setup-link"))).andExpect(status().isNotFound());
    String owner = login(c.code(), "owner", "fresh1");
    mvc.perform(as(owner, post(url))).andExpect(status().isUnauthorized());
  }

  @Test
  void clearsAShopUsersAuthenticator() throws Exception {
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD); // ผูกตอน login
    String url = "/platform/companies/" + c.id() + "/mfa-reset";
    mvc.perform(as(platform, post(url), Map.of("username", "nobody")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ไม่พบชื่อผู้ใช้นี้ในบริษัท"), true));
    mvc.perform(as(owner, post(url), Map.of("username", "owner"))).andExpect(status().isUnauthorized());
    mvc.perform(as(platform, post(url), Map.of("username", " Owner "))).andExpect(status().isOk()).andExpect(content().string(""));
    mvc.perform(as(platform, post(url), Map.of("username", "owner")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ผู้ใช้นี้ยังไม่ได้ผูก Microsoft Authenticator"), true));
    mvc.perform(as(platform, post("/platform/companies/999999/mfa-reset"), Map.of("username", "owner"))).andExpect(status().isNotFound());
    assertThat(platformAudit("mfa_reset"))
        .containsExactly("platform|-|" + PLATFORM_NAME + "|owner|ผู้ให้บริการล้าง Microsoft Authenticator ให้");
    // ล้างแล้ว = ผูกใหม่ตอน login
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("companyCode", c.code(), "username", "owner", "password", TestCompanies.PASSWORD))))
        .andExpect(jsonPath("$.mfa").value("setup"));
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
