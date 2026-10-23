package com.an22.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.config.ClockConfig;
import com.an22.api.rules.SignupRules;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import com.an22.api.support.TestMailer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** สมัครใช้งาน (E37) + ลืมรหัสผ่าน / ตั้งรหัสด้วยลิงก์ (E41) — public endpoint, ข้อความตาม mock. */
class AccountIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired TestMailer mailer;

  private ResultActions call(String path, Object body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
  }

  private static Map<String, Object> signup(String code) {
    Map<String, Object> p = new HashMap<>();
    p.put("companyCode", " " + code.toLowerCase() + " ");
    p.put("companyName", " บริษัท แอคมี จำกัด ");
    p.put("taxId", "0105550123451");
    p.put("vatRegistered", true);
    p.put("branch", Map.of("code", "hq", "name", "สำนักงานใหญ่", "branchNo", "00000", "address", "กรุงเทพฯ", "phone", ""));
    p.put("owner", Map.of("name", "สมศักดิ์", "username", "Boss", "email", "boss@acme.test", "password", "secret"));
    p.put("planId", "starter");
    return p;
  }

  private static String newCode() {
    return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  @Test
  void signupCreatesATrialCompanyAndLogsTheOwnerIn() throws Exception {
    String code = newCode();
    String trialEnd = LocalDate.now(ClockConfig.BANGKOK).plusDays(14).toString();
    String body = call("/auth/signup", signup(code)).andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString())
        .andExpect(jsonPath("$.user.username").value("boss")).andExpect(jsonPath("$.user.role").value("owner"))
        .andExpect(jsonPath("$.user.allBranches").value(true))
        .andExpect(jsonPath("$.company.code").value(code)).andExpect(jsonPath("$.company.name").value("บริษัท แอคมี จำกัด"))
        .andExpect(jsonPath("$.branches[0].code").value("HQ"))
        .andExpect(jsonPath("$.branches[0].receiptFooter").value("ขอบคุณที่ใช้บริการ"))
        .andExpect(jsonPath("$.billing").value(org.hamcrest.Matchers.nullValue()))
        .andReturn().getResponse().getContentAsString();
    String token = JSON.readTree(body).path("token").asString();

    mvc.perform(as(token, get("/company/subscription"))).andExpect(jsonPath("$.planId").value("starter"))
        .andExpect(jsonPath("$.status").value("trial")).andExpect(jsonPath("$.subscription.trialEndsOn").value(trialEnd))
        .andExpect(jsonPath("$.subscription.nextInvoiceOn").value(LocalDate.parse(trialEnd).plusDays(1).toString()));
    mvc.perform(as(token, get("/payment-methods"))).andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].code").value("CASH")).andExpect(jsonPath("$[0].cashRounding").value("0.25"));
    mvc.perform(as(token, get("/settings/company"))).andExpect(jsonPath("$.taxId").value("0105550123451"))
        .andExpect(jsonPath("$.exchangeDays").value(7));
    mvc.perform(as(token, get("/company/onboarding")))
        .andExpect(content().json("{\"terminals\":0,\"categories\":0,\"products\":0,\"prices\":0,\"users\":1}", true));
    assertThat(login(code, "boss", "secret")).isNotEmpty();
    long id = sql.platform(jdbc -> jdbc.sql("SELECT id FROM dt_companies WHERE code = :c").param("c", code).query(Long.class).single());
    List<String> signups = sql.platform(jdbc -> jdbc.sql("SELECT action || '|' || ref_label || '|' || detail || '|' || user_name"
            + " FROM dt_audit_log WHERE tenant_id = :t AND action = 'signup'").param("t", id).query(String.class).list());
    assertThat(signups).containsExactly("signup|" + code + "|สมัครใช้งาน แพ็กเกจ Starter ทดลองใช้ถึง " + trialEnd + "|สมศักดิ์");

    // รหัสเดิม (ตัวพิมพ์ต่าง) ใช้ไม่ได้อีก — รวมบริษัทที่ถูกลบแล้ว
    call("/auth/signup", signup(code)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("รหัสบริษัทนี้ถูกใช้แล้ว"), true));
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET deleted_at = now() WHERE id = :id").param("id", id).update());
    call("/auth/signup", signup(code)).andExpect(content().json(message("รหัสบริษัทนี้ถูกใช้แล้ว"), true));
  }

  @Test
  void signupChecksTheFormInOrder() throws Exception {
    Map<String, Object> p = signup(newCode());
    p.put("companyName", " ");
    call("/auth/signup", p).andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากรอกชื่อบริษัท"), true));
    p = signup("ab");
    call("/auth/signup", p).andExpect(content().json(message("รหัสบริษัทใช้ได้เฉพาะ A-Z, 0-9 ยาว 3–20 ตัว"), true));
    p = signup(newCode());
    p.put("owner", Map.of("name", "ส", "username", "boss", "email", "", "password", "secret"));
    call("/auth/signup", p).andExpect(content().json(message("เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)"), true));
    p = signup(newCode());
    p.put("planId", "gold");
    call("/auth/signup", p).andExpect(content().json(message("กรุณาเลือกแพ็กเกจ"), true));
    p = signup(newCode());
    p.remove("branch");
    call("/auth/signup", p).andExpect(status().isBadRequest());
  }

  @Test
  void forgotPasswordAnswersTheSameAndMailsALinkFromTheConfiguredBaseUrl() throws Exception {
    Company c = companies.create();
    String email = "owner@" + c.code().toLowerCase() + ".test";
    String answer = message(SignupRules.FORGOT_PASSWORD_ANSWER);
    for (Map<String, String> wrong : List.of(Map.of("companyCode", c.code(), "email", "nobody@x.test"),
        Map.of("companyCode", "NOPE" + c.code(), "email", email), Map.of("companyCode", c.code(), "email", ""))) {
      call("/auth/forgot-password", wrong).andExpect(status().isOk()).andExpect(content().json(answer, true));
    }
    assertThat(mailer.to(email)).isEmpty();

    // ลิงก์จาก APP_BASE_URL ไม่ใช่ Host ของ request
    mvc.perform(post("/auth/forgot-password").header("Host", "evil.example").header("X-Forwarded-Host", "evil.example")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("companyCode", c.code().toLowerCase(), "email", " OWNER@" + c.code() + ".test "))))
        .andExpect(status().isOk()).andExpect(content().json(answer, true));
    List<TestMailer.Mail> mails = mailer.to(email);
    assertThat(mails).hasSize(1);
    assertThat(mails.getFirst().subject()).isEqualTo("ตั้งรหัสผ่านใหม่ — บริษัท " + c.code());
    String link = mails.getFirst().body();
    assertThat(link).startsWith("https://shop.example.test/auth/reset-password?token=").doesNotContain("evil");
    String token = link.substring(link.indexOf("token=") + "token=".length());
    // DB เก็บแค่ hash
    List<byte[]> stored = sql.platform(jdbc -> jdbc.sql("SELECT token_hash FROM dt_password_reset_tokens WHERE company_id = :c")
        .param("c", c.id()).query(byte[].class).list());
    assertThat(stored).hasSize(1);
    assertThat(new String(stored.getFirst(), StandardCharsets.ISO_8859_1)).doesNotContain(token);

    String invalid = message("ลิงก์ตั้งรหัสผ่านไม่ถูกต้อง หมดอายุ หรือใช้ไปแล้ว — ขอลิงก์ใหม่");
    call("/auth/reset-password", Map.of("token", "nope", "password", "abcd")).andExpect(status().isBadRequest())
        .andExpect(content().json(invalid, true));
    call("/auth/reset-password", Map.of("token", token, "password", "ab")).andExpect(status().isBadRequest())
        .andExpect(content().json(message("รหัสผ่านต้องมีอย่างน้อย 4 ตัว"), true));
    call("/auth/reset-password", Map.of("token", token, "password", "newpass")).andExpect(status().isOk());
    assertThat(login(c.code(), "owner", "newpass")).isNotEmpty();
    call("/auth/reset-password", Map.of("token", token, "password", "again")).andExpect(content().json(invalid, true));
    assertThat(companies.audit(c, "password_reset")).containsExactly("password_reset|owner|ตั้งรหัสผ่านใหม่ด้วยลิงก์ทางอีเมล");

    // หมดอายุ
    call("/auth/forgot-password", Map.of("companyCode", c.code(), "email", email));
    String second = mailer.to(email).getLast().body();
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_password_reset_tokens SET expires_at = now() - interval '1 minute' WHERE company_id = :c")
        .param("c", c.id()).update());
    call("/auth/reset-password", Map.of("token", second.substring(second.indexOf("token=") + 6), "password", "later"))
        .andExpect(content().json(invalid, true));
  }

  @Test
  void onlyAnActiveOwnerGetsALink() throws Exception {
    Company c = companies.create();
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_users SET email = :e WHERE tenant_id = :t AND username = 'boss'")
        .param("e", "boss@" + c.code().toLowerCase() + ".test").param("t", c.id()).update());
    call("/auth/forgot-password", Map.of("companyCode", c.code(), "email", "boss@" + c.code().toLowerCase() + ".test"));
    assertThat(mailer.to("boss@" + c.code().toLowerCase() + ".test")).isEmpty();
    String email = "owner@" + c.code().toLowerCase() + ".test";
    call("/auth/forgot-password", Map.of("companyCode", c.code(), "email", email));
    String link = mailer.to(email).getFirst().body();
    companies.setUserActive(c, c.owner(), false);
    call("/auth/reset-password", Map.of("token", link.substring(link.indexOf("token=") + 6), "password", "newpass"))
        .andExpect(status().isBadRequest());
  }
}
