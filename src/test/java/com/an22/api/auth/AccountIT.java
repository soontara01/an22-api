package com.an22.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.rules.SignupRules;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import com.an22.api.support.TestMailer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** ลืมรหัสผ่าน / ตั้งรหัสด้วยลิงก์ (E41) + ไม่มีสมัครเองแล้ว (E59) — public endpoint, ข้อความตาม mock. */
class AccountIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired TestMailer mailer;

  /** IP ต่างกันทุก test — ตัวนับต่อ IP ไม่ข้าม test. */
  private final String ip = "198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));

  private ResultActions call(String path, Object body) throws Exception {
    return call(path, body, ip);
  }

  private ResultActions call(String path, Object body, String fromIp) throws Exception {
    return mvc.perform(post(path).header("X-Real-IP", fromIp).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
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
    mvc.perform(post("/auth/forgot-password").header("X-Real-IP", ip).header("Host", "evil.example").header("X-Forwarded-Host", "evil.example")
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

  @Test
  void signupIsGone() throws Exception {
    // E59: บริษัทใหม่สร้างได้ทางเดียวคือ platform — /auth/signup ไม่ใช่ public path แล้ว (ไม่มี token → 401) และไม่สร้างอะไร
    long before = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM dt_companies").query(Long.class).single());
    call("/auth/signup", Map.of("companyCode", "S" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "companyName", "x"))
        .andExpect(status().isUnauthorized());
    long after = sql.platform(jdbc -> jdbc.sql("SELECT count(*) FROM dt_companies").query(Long.class).single());
    assertThat(after).isEqualTo(before);
  }

  @Test
  void forgotPasswordIsRateLimited() throws Exception {
    String tooMany = message(LoginThrottle.TOO_MANY);
    // ต่อรหัสบริษัท + อีเมล: นับแม้ไม่มีจริง (429 ไม่บอกว่ามีหรือไม่)
    Map<String, String> unknown = Map.of("companyCode", "NOPE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
        "email", "who@x.test");
    for (int i = 0; i < AccountService.FORGOT_ACCOUNT_MAX; i++) {
      call("/auth/forgot-password", unknown).andExpect(status().isOk());
    }
    call("/auth/forgot-password", unknown).andExpect(status().isTooManyRequests()).andExpect(content().json(tooMany, true));
    Company c = companies.create();
    String email = "owner@" + c.code().toLowerCase() + ".test";
    Map<String, String> real = Map.of("companyCode", c.code(), "email", email);
    for (int i = 0; i < AccountService.FORGOT_ACCOUNT_MAX; i++) {
      call("/auth/forgot-password", real, "192.0.2." + (10 + i)).andExpect(status().isOk());
    }
    call("/auth/forgot-password", real, "192.0.2.99").andExpect(status().isTooManyRequests());
    assertThat(mailer.to(email)).hasSize(AccountService.FORGOT_ACCOUNT_MAX);
  }
}
