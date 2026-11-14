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

  private Company c;
  /** IP ต่างกันทุก test — ตัวนับต่อ IP ไม่ข้าม test. */
  private final String ip = "198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));

  @BeforeEach
  void company() {
    c = companies.create();
  }

  private ResultActions loginAttempt(String username, boolean mfa) throws Exception {
    Map<String, Object> body = new HashMap<>(Map.of("companyCode", c.code(), "username", username, "password", TestCompanies.PASSWORD));
    if (mfa) {
      body.put("mfa", true);
    }
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

  /** ผูก Authenticator ให้ผู้ใช้ผ่าน {@code /auth/login-mfa} → secret. */
  private String enrol(String username) throws Exception {
    JsonNode challenge = json(loginAttempt(username, true).andExpect(status().isOk()));
    String secret = challenge.path("setup").path("secret").asString();
    verify(challenge.path("challenge").asString(), codeNow(secret, 0)).andExpect(status().isOk());
    return secret;
  }

  private void setRequirement(String owner, String rule) throws Exception {
    JsonNode info = json(mvc.perform(as(owner, get("/settings/company"))));
    Map<String, Object> next = JSON.convertValue(info, Map.class);
    next.put("mfaRequired", rule);
    next.put("vatRegistered", false); // บริษัททดสอบไม่มีเลขภาษี
    mvc.perform(as(owner, put("/settings/company"), next)).andExpect(status().isOk());
  }

  @Test
  void plainLoginStaysOneStepWithoutAuthenticator() throws Exception {
    loginAttempt("staff", false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString())
        .andExpect(jsonPath("$.user.mfaEnabled").value(false))
        .andExpect(jsonPath("$.mfa").doesNotExist());
  }

  @Test
  void loginMfaSetsUpThenLogsInOnTheFirstRightCode() throws Exception {
    JsonNode challenge = json(loginAttempt("staff", true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.mfa").value("setup"))
        .andExpect(jsonPath("$.challenge").isString()));
    String secret = challenge.path("setup").path("secret").asString();
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(challenge.path("setup").path("otpauthUrl").asString())
        .isEqualTo("otpauth://totp/AN22%3A" + c.code() + "%3Astaff?secret=" + secret + "&issuer=AN22&algorithm=SHA1&digits=6&period=30");
    // ลองใหม่ได้ secret เดิม (อาจสแกนไปแล้ว) — ยังไม่ audit login
    assertThat(json(loginAttempt("staff", true)).path("setup").path("secret").asString()).isEqualTo(secret);
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
    JsonNode challenge = json(loginAttempt("staff", false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mfa").value("verify"))
        .andExpect(jsonPath("$.setup").doesNotExist())
        .andExpect(jsonPath("$.token").doesNotExist()));
    String token = challenge.path("challenge").asString();
    verify(token, codeNow(secret, 0)).andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    verify(token, codeNow(secret, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
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
    String challenge = json(loginAttempt("staff", true)).path("challenge").asString();
    mvc.perform(as(challenge, get("/me"))).andExpect(status().isUnauthorized());
  }

  @Test
  void wrongCodesCountInTheAccountsLoginLimit() throws Exception {
    String challenge = json(loginAttempt("staff", true)).path("challenge").asString();
    for (int i = 0; i < 5; i++) {
      verify(challenge, "000000").andExpect(status().isBadRequest());
    }
    verify(challenge, "000000").andExpect(status().isTooManyRequests());
    // รหัสผ่านถูกก็ไม่ล้างตัวนับระหว่างล็อก
    loginAttempt("staff", true).andExpect(status().isTooManyRequests());
  }

  @Test
  void companyRequirementAppliesAtThePlainLoginToo() throws Exception {
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    setRequirement(owner, "admins");
    loginAttempt("staff", false).andExpect(jsonPath("$.token").isString());
    loginAttempt("boss", false).andExpect(jsonPath("$.mfa").value("setup"));
    setRequirement(owner, "all");
    loginAttempt("staff", false).andExpect(jsonPath("$.mfa").value("setup"));
    assertThat(companies.audit(c, "company_settings")).last().asString()
        .contains("บังคับ Microsoft Authenticator: เจ้าของ + ผู้ดูแล → ทุกคน");

    Map<String, Object> bad = JSON.convertValue(json(mvc.perform(as(owner, get("/settings/company")))), Map.class);
    bad.put("mfaRequired", "x");
    bad.put("vatRegistered", false);
    mvc.perform(as(owner, put("/settings/company"), bad))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("การบังคับใช้ Microsoft Authenticator ไม่ถูกต้อง")));
  }

  @Test
  void userTurnsTheirOwnOnAndOff() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String secret = json(mvc.perform(as(staff, post("/me/mfa/setup"), Map.of())).andExpect(status().isOk())).path("secret").asString();
    mvc.perform(as(staff, post("/me/mfa/confirm"), Map.of("code", "000000")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message(WRONG_CODE)));
    mvc.perform(as(staff, post("/me/mfa/confirm"), Map.of("code", codeNow(secret, 0))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mfaEnabled").value(true));
    mvc.perform(as(staff, post("/me/mfa/setup"), Map.of()))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เปิดใช้ Microsoft Authenticator อยู่แล้ว")));
    mvc.perform(as(staff, post("/me/mfa/disable"), Map.of("password", "x")).header("X-Real-IP", ip))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านไม่ถูกต้อง")));

    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    setRequirement(owner, "all");
    mvc.perform(as(staff, post("/me/mfa/disable"), Map.of("password", TestCompanies.PASSWORD)).header("X-Real-IP", ip))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("บริษัทบังคับให้ใช้ Microsoft Authenticator — ปิดเองไม่ได้")));
    setRequirement(owner, "none");
    mvc.perform(as(staff, post("/me/mfa/disable"), Map.of("password", TestCompanies.PASSWORD)).header("X-Real-IP", ip))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mfaEnabled").value(false));
    assertThat(companies.audit(c, "mfa_enable")).containsExactly("mfa_enable|staff|เปิดใช้จากหน้าตั้งค่า");
    assertThat(companies.audit(c, "mfa_disable")).containsExactly("mfa_disable|staff|ปิดใช้จากหน้าตั้งค่า");
  }

  @Test
  void adminClearsALostPhoneButNeverTheirOwn() throws Exception {
    // token ของ staff ออกก่อนผูก (ยังใช้ได้) — ผูกแล้ว login ธรรมดาได้ challenge
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    enrol("staff");
    String boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    mvc.perform(as(boss, get("/users/" + c.staff()))).andExpect(jsonPath("$.mfaEnabled").value(true));
    // แบบฟอร์มผู้ใช้เขียน mfaEnabled ไม่ได้
    mvc.perform(as(boss, put("/users/" + c.staff()), Map.of("mfaEnabled", false))).andExpect(jsonPath("$.mfaEnabled").value(true));
    mvc.perform(as(staff, delete("/users/" + c.staff() + "/mfa"))).andExpect(status().isForbidden());
    mvc.perform(as(boss, delete("/users/" + c.boss() + "/mfa")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ปิดได้ที่หน้าตั้งค่า")));
    mvc.perform(as(boss, delete("/users/" + c.staff() + "/mfa")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mfaEnabled").value(false));
    mvc.perform(as(boss, delete("/users/" + c.staff() + "/mfa")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(message("ผู้ใช้นี้ยังไม่ได้เปิดใช้ Microsoft Authenticator")));
    loginAttempt("staff", false).andExpect(jsonPath("$.token").isString());
    assertThat(companies.audit(c, "mfa_reset")).containsExactly("mfa_reset|staff|ผู้ดูแลระบบล้าง Microsoft Authenticator ให้");
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
