package com.an22.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.rules.TotpRules;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** login สองขั้นด้วย Microsoft Authenticator (E55) — ลำดับ / ข้อความตาม mock ({@code mock-backend.interceptor.spec.ts}). */
class MfaLoginIT extends ApiTest {

  private static final String WRONG_CODE = "รหัสยืนยันไม่ถูกต้อง";
  private static final String EXPIRED = "หมดเวลายืนยันรหัส กรุณาเข้าสู่ระบบใหม่";

  @Autowired TestCompanies companies;
  @Autowired com.an22.api.support.TenantSql sql;

  private Company c;
  /** IP ต่างกันทุก test — ตัวนับต่อ IP ไม่ข้าม test. */
  private final String ip = "198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));

  @BeforeEach
  void company() {
    c = companies.create();
  }

  private ResultActions loginAttempt(String username) throws Exception {
    Map<String, Object> body = new HashMap<>(Map.of("companyCode", c.code(), "username", username, "password", TestCompanies.PASSWORD));
    return mvc.perform(post("/auth/login").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
  }

  private JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private ResultActions verify(String challenge, String code) throws Exception {
    var request = post("/auth/mfa/verify").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("code", code)));
    if (challenge != null) {
      request.header("Authorization", bearer(challenge));
    }
    return mvc.perform(request);
  }

  private static String codeNow(String secret, int ahead) {
    return TotpRules.code(secret, TotpRules.step(System.currentTimeMillis()) + ahead);
  }

  /** ช่วงที่รหัสตอนผูกถูกใช้ — รหัสต่อจากนี้คิดจากช่วงนี้ ไม่ขึ้นกับว่านาฬิกาเดินข้ามช่วงระหว่าง test (ไม่ flaky). */
  private long enrolStep;

  /** ผูก Authenticator ให้ผู้ใช้ตอน login ครั้งแรก → secret. */
  private String enrol(String username) throws Exception {
    // login() ของ ApiTest อาจผูกไว้แล้ว — เริ่มจากยังไม่ผูก
    sql.platform(jdbc -> jdbc.sql("DELETE FROM dt_user_mfa m USING dt_users u WHERE m.tenant_id = u.tenant_id AND m.user_id = u.id"
        + " AND u.tenant_id = :t AND u.username = :u").param("t", c.id()).param("u", username).update());
    JsonNode challenge = json(loginAttempt(username).andExpect(status().isOk()));
    String secret = challenge.path("setup").path("secret").asString();
    enrolStep = TotpRules.step(System.currentTimeMillis());
    verify(challenge.path("challenge").asString(), TotpRules.code(secret, enrolStep)).andExpect(status().isOk());
    return secret;
  }

  @Test
  void everyLoginGoesThroughTheCodeAndTheFirstOneSetsItUp() throws Exception {
    JsonNode challenge = json(loginAttempt("staff")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.mfa").value("setup"))
        .andExpect(jsonPath("$.challenge").isString()));
    String secret = challenge.path("setup").path("secret").asString();
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(challenge.path("setup").path("otpauthUrl").asString())
        .isEqualTo("otpauth://totp/AN22%3A" + c.code() + "%3Astaff?secret=" + secret + "&issuer=AN22&algorithm=SHA1&digits=6&period=30");
    // ลองใหม่ได้ secret เดิม (อาจสแกนไปแล้ว) — ยังไม่ audit login
    assertThat(json(loginAttempt("staff")).path("setup").path("secret").asString()).isEqualTo(secret);
    assertThat(companies.audit(c, "login")).isEmpty();

    String token = challenge.path("challenge").asString();
    verify(token, "000000").andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    verify(token, codeNow(secret, 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString())
        .andExpect(jsonPath("$.user.username").value("staff"))
        .andExpect(jsonPath("$.user.mfaEnabled").value(true));
    assertThat(companies.audit(c, "mfa_enable")).containsExactly("mfa_enable|staff|ผูกตอนเข้าสู่ระบบ");
    assertThat(companies.audit(c, "login")).containsExactly("login||ด้วย Microsoft Authenticator");
    assertThat(companies.audit(c, "login_failed")).containsExactly("login_failed||รหัส Microsoft Authenticator ไม่ถูกต้อง");
  }

  @Test
  void neverSkipsTheCodeOnceOnAndAUsedCodeNeverPassesAgain() throws Exception {
    String secret = enrol("staff");
    JsonNode challenge = json(loginAttempt("staff")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mfa").value("verify"))
        .andExpect(jsonPath("$.setup").doesNotExist())
        .andExpect(jsonPath("$.token").doesNotExist()));
    String token = challenge.path("challenge").asString();
    verify(token, TotpRules.code(secret, enrolStep)).andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    verify(token, TotpRules.code(secret, enrolStep + 1)).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
  }

  @Test
  void codeStepWithoutAValidChallengeIs400NotA401() throws Exception {
    verify(null, "123456").andExpect(status().isBadRequest()).andExpect(content().json(message(EXPIRED)));
    verify("garbage", "123456").andExpect(status().isBadRequest()).andExpect(content().json(message(EXPIRED)));
    // token ร้านใช้แทน challenge ไม่ได้ (aud ต่างกัน)
    String shop = login(c.code(), "staff", TestCompanies.PASSWORD);
    verify(shop, "123456").andExpect(status().isBadRequest()).andExpect(content().json(message(EXPIRED)));
  }

  @Test
  void challengeIsNotAShopToken() throws Exception {
    String challenge = json(loginAttempt("staff")).path("challenge").asString();
    mvc.perform(as(challenge, get("/me"))).andExpect(status().isUnauthorized());
  }

  @Test
  void wrongCodesCountInTheAccountsLoginLimit() throws Exception {
    String challenge = json(loginAttempt("staff")).path("challenge").asString();
    for (int i = 0; i < 5; i++) {
      verify(challenge, "000000").andExpect(status().isBadRequest());
    }
    verify(challenge, "000000").andExpect(status().isTooManyRequests());
    // รหัสผ่านถูกก็ไม่ล้างตัวนับระหว่างล็อก
    loginAttempt("staff").andExpect(status().isTooManyRequests());
  }

  @Test
  void adminClearsALostPhoneButNeverTheirOwn() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    enrol("staff");
    String boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    mvc.perform(as(boss, get("/users/" + c.staff()))).andExpect(jsonPath("$.mfaEnabled").value(true));
    // แบบฟอร์มผู้ใช้เขียน mfaEnabled ไม่ได้
    mvc.perform(as(boss, put("/users/" + c.staff()), Map.of("mfaEnabled", false))).andExpect(jsonPath("$.mfaEnabled").value(true));
    mvc.perform(as(staff, delete("/users/" + c.staff() + "/mfa"))).andExpect(status().isForbidden());
    mvc.perform(as(boss, delete("/users/" + c.boss() + "/mfa")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ให้ผู้ดูแลระบบคนอื่นล้างให้")));
    mvc.perform(as(boss, delete("/users/" + c.staff() + "/mfa")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mfaEnabled").value(false));
    mvc.perform(as(boss, delete("/users/" + c.staff() + "/mfa")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("ผู้ใช้นี้ยังไม่ได้ผูก Microsoft Authenticator")));
    // ล้างแล้ว = ผูกใหม่ตอน login (ไม่มีทางข้ามขั้นรหัส)
    loginAttempt("staff").andExpect(jsonPath("$.mfa").value("setup")).andExpect(jsonPath("$.token").doesNotExist());
    assertThat(companies.audit(c, "mfa_reset")).containsExactly("mfa_reset|staff|ผู้ดูแลระบบล้าง Microsoft Authenticator ให้");
  }

  @Test
  void movesOwnAuthenticatorToANewPhoneWithThePasswordAndTheOldPhone() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String old = enrol("staff");
    var start = post("/me/mfa/change");
    mvc.perform(as(staff, start, Map.of("password", "nope", "code", TotpRules.code(old, enrolStep + 1))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านไม่ถูกต้อง")));
    mvc.perform(as(staff, post("/me/mfa/change"), Map.of("password", TestCompanies.PASSWORD, "code", "000000")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    JsonNode change = json(mvc.perform(as(staff, post("/me/mfa/change"),
            Map.of("password", TestCompanies.PASSWORD, "code", TotpRules.code(old, enrolStep + 1))))
        .andExpect(status().isOk()));
    String fresh = change.path("setup").path("secret").asString();
    String proof = change.path("change").asString();
    assertThat(fresh).matches("[A-Z2-7]{32}").isNotEqualTo(old);
    assertThat(change.path("setup").path("otpauthUrl").asString()).contains("AN22%3A" + c.code() + "%3Astaff");
    // หลักฐานใช้แทน token / challenge ไม่ได้ และกลับกัน
    mvc.perform(as(proof, get("/me"))).andExpect(status().isUnauthorized());
    String newCode = codeNow(fresh, 0);
    mvc.perform(as(staff, post("/me/mfa/change/confirm"), Map.of("change", staff, "code", newCode)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("หมดเวลาเปลี่ยนเครื่อง กรุณาเริ่มใหม่")));
    // ของคนอื่นใช้ไม่ได้
    String boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    mvc.perform(as(boss, post("/me/mfa/change/confirm"), Map.of("change", proof, "code", newCode)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("หมดเวลาเปลี่ยนเครื่อง กรุณาเริ่มใหม่")));
    mvc.perform(as(staff, post("/me/mfa/change/confirm"), Map.of("change", proof, "code", "000000")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    mvc.perform(as(staff, post("/me/mfa/change/confirm"), Map.of("change", proof, "code", newCode)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mfaEnabled").value(true));
    // ส่งซ้ำ = ใช้ไปแล้ว
    mvc.perform(as(staff, post("/me/mfa/change/confirm"), Map.of("change", proof, "code", newCode)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("หมดเวลาเปลี่ยนเครื่อง กรุณาเริ่มใหม่")));
    assertThat(companies.audit(c, "mfa_enable")).contains("mfa_enable|staff|เปลี่ยนเครื่องจากหน้าตั้งค่า");

    // เครื่องเดิมใช้ไม่ได้อีก เครื่องใหม่ใช้ได้
    long next = TotpRules.step(System.currentTimeMillis()) + 1;
    String challenge = json(loginAttempt("staff").andExpect(jsonPath("$.mfa").value("verify"))).path("challenge").asString();
    verify(challenge, TotpRules.code(old, next)).andExpect(status().isBadRequest());
    verify(challenge, TotpRules.code(fresh, next)).andExpect(status().isOk());
  }

  @Test
  void secretsNeverLeaveInTheCompanyExport() throws Exception {
    enrol("staff");
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    JsonNode data = json(mvc.perform(as(owner, get("/company/export"))).andExpect(status().isOk())).path("data");
    assertThat(data.has("userMfa")).isFalse();
    assertThat(data.toString()).doesNotContain("secret");
  }

  @Test
  void deletingAUserRemovesTheirAuthenticator() throws Exception {
    enrol("staff");
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(as(owner, delete("/users/" + c.staff()))).andExpect(status().isOk());
  }
}
