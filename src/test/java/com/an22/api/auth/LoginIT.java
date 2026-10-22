package com.an22.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** {@code POST /auth/login} — ลำดับ / ข้อความตาม mock (E3: ผิดทุกกรณีข้อความเดียว). */
@ExtendWith(OutputCaptureExtension.class)
class LoginIT extends ApiTest {

  private static final String WRONG = "{\"message\":\"รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง\"}";

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  /** IP ต่างกันทุก test — ตัวนับต่อ IP ไม่ข้าม test. */
  private final String ip = "198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));

  private ResultActions attempt(String code, String username, String password) throws Exception {
    return mvc.perform(post("/auth/login")
        .header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("companyCode", code, "username", username, "password", password))));
  }

  private List<String> audit(long tenant, String action) {
    return sql.platform(jdbc -> jdbc.sql(
            "SELECT coalesce(user_id::text, '-') || ':' || user_name FROM dt_audit_log WHERE tenant_id = :t AND action = :a ORDER BY id")
        .param("t", tenant).param("a", action).query(String.class).list());
  }

  @Test
  void demoOwnerGetsFullSessionAndToken() throws Exception {
    attempt("demo", " Admin ", "admin")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString())
        .andExpect(jsonPath("$.user.username").value("admin"))
        .andExpect(jsonPath("$.user.role").value("owner"))
        .andExpect(jsonPath("$.user.allBranches").value(true))
        .andExpect(jsonPath("$.user.createdAt").value("2026-01-05T09:00:00Z"))
        .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.company.code").value("DEMO"))
        .andExpect(jsonPath("$.company.name").value("บริษัท ไอทีดี คอมพิวเตอร์ จำกัด"))
        .andExpect(jsonPath("$.company.deletionPurgeAfter").value((Object) null))
        .andExpect(jsonPath("$.branches[*].code", Matchers.contains("HQ", "BN")))
        .andExpect(jsonPath("$.billing").value((Object) null))
        .andExpect(jsonPath("$.canSeeCost").value(true));
  }

  @Test
  void staffSeesOnlyTheirBranchesAndNoCost() throws Exception {
    attempt("DEMO", "somchai", "1234")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.branchIds", Matchers.contains(1)))
        .andExpect(jsonPath("$.branches[*].code", Matchers.contains("HQ")))
        .andExpect(jsonPath("$.canSeeCost").value(false));
  }

  @Test
  void everyWrongLoginGetsTheSameMessage() throws Exception {
    Company c = companies.create();
    attempt("NOPE" + c.code(), "owner", TestCompanies.PASSWORD).andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
    attempt(c.code(), "nobody", TestCompanies.PASSWORD).andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
    attempt(c.code(), "owner", "wrong").andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
    attempt(c.code(), "gone", TestCompanies.PASSWORD).andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
    // login_failed: ชื่อที่พิมพ์ ไม่มี userId — บันทึกแม้ request ตอบ 401 (rollback)
    assertThat(audit(c.id(), "login_failed")).containsExactly("-:nobody", "-:owner", "-:gone");
    assertThat(audit(c.id(), "login")).isEmpty();
  }

  @Test
  void successfulLoginIsAudited() throws Exception {
    Company c = companies.create();
    attempt(c.code(), "staff", TestCompanies.PASSWORD).andExpect(status().isOk());
    assertThat(audit(c.id(), "login")).containsExactly(c.staff() + ":staff");
    String kind = sql.platform(jdbc -> jdbc.sql(
            "SELECT created_by_kind || ':' || created_by FROM dt_audit_log WHERE tenant_id = :t AND action = 'login'")
        .param("t", c.id()).query(String.class).single());
    assertThat(kind).isEqualTo("user:" + c.staff());
  }

  @Test
  void suspendedCompanyIsToldOnlyAfterTheRightPassword() throws Exception {
    Company c = companies.create();
    companies.suspend(c, "overdue");
    attempt(c.code(), "owner", "wrong").andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
    attempt(c.code(), "owner", TestCompanies.PASSWORD)
        .andExpect(status().isUnauthorized())
        .andExpect(content().json(
            "{\"message\":\"บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน\"}", true));
    companies.suspend(c, "platform");
    attempt(c.code(), "staff", TestCompanies.PASSWORD)
        .andExpect(content().json("{\"message\":\"บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ\"}", true));
    assertThat(audit(c.id(), "login")).isEmpty();
  }

  @Test
  void deletionPendingLetsOnlyTheOwnerIn() throws Exception {
    Company c = companies.create();
    companies.suspend(c, "deletion");
    attempt(c.code(), "boss", TestCompanies.PASSWORD)
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"บริษัทนี้อยู่ระหว่างรอลบข้อมูล — ติดต่อเจ้าของบริษัท\"}", true));
    attempt(c.code(), "owner", TestCompanies.PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.company.active").value(false))
        .andExpect(jsonPath("$.company.deletionPurgeAfter").value("2026-12-01"));
  }

  @Test
  void deletedCompanyIsUnknown() throws Exception {
    Company c = companies.create();
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET deleted_at = now() WHERE id = :id").param("id", c.id()).update());
    attempt(c.code(), "owner", TestCompanies.PASSWORD).andExpect(status().isUnauthorized()).andExpect(content().json(WRONG, true));
  }

  @Test
  void ownerAndAdminGetTheBillingNotice() throws Exception {
    Company c = companies.create();
    sql.platform(jdbc -> jdbc.sql(
            "INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status)"
                + " VALUES (:c, 'SUB-209901-' || lpad(:c::text, 4, '0'), 'pro', DATE '2099-01-01', DATE '2099-01-31', 3490, DATE '2099-01-01', DATE '2099-01-08', 'open')")
        .param("c", c.id()).update());
    attempt(c.code(), "boss", TestCompanies.PASSWORD)
        .andExpect(jsonPath("$.billing.level").value("info"))
        .andExpect(jsonPath("$.billing.text").value(Matchers.startsWith("ใบแจ้งหนี้ SUB-209901-")));
    attempt(c.code(), "staff", TestCompanies.PASSWORD).andExpect(jsonPath("$.billing").value((Object) null));
  }

  @Test
  void passwordNeverReachesTheLog(CapturedOutput output) throws Exception {
    attempt("DEMO", "admin", "secret-password-xyz").andExpect(status().isUnauthorized());
    assertThat(output.getOut()).doesNotContain("secret-password-xyz");
  }
}
