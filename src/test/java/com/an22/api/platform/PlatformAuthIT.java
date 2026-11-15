package com.an22.api.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.rules.TotpRules;
import com.an22.api.security.JwtService;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** login / session ของ platform admin (E36) — token แยกจากร้าน; ต้องผ่านรหัส Microsoft Authenticator เสมอ (E56). */
class PlatformAuthIT extends ApiTest {

  private static final String EXPIRED = "{\"message\":\"เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่\"}";

  @Autowired JwtService jwt;
  @Autowired TenantSql sql;

  /** ทุก test เริ่มจาก admin ที่ยังไม่ผูก Authenticator (ตารางของ platform ใช้ร่วมทุก test). */
  @BeforeEach
  void noAuthenticator() {
    sql.platform(jdbc -> jdbc.sql("DELETE FROM dt_platform_admin_mfa").update());
  }

  private tools.jackson.databind.JsonNode passwordStep(String username, String password) throws Exception {
    String body = mvc.perform(post("/platform/auth/login").header("X-Real-IP", "198.18.0.1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    return JSON.readTree(body);
  }

  private org.springframework.test.web.servlet.ResultActions codeStep(String challenge, String code) throws Exception {
    var request = post("/platform/auth/mfa/verify").header("X-Real-IP", "198.18.0.1").contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("code", code)));
    if (challenge != null) {
      request.header("Authorization", bearer(challenge));
    }
    return mvc.perform(request);
  }

  /** login ครบสองขั้น (ผูกครั้งแรก) → token ของ platform. */
  private String platformLogin(String username, String password) throws Exception {
    var challenge = passwordStep(username, password);
    String secret = challenge.path("setup").path("secret").asString();
    String body = codeStep(challenge.path("challenge").asString(), TotpRules.code(secret, TotpRules.step(System.currentTimeMillis())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin.username").value("platform"))
        .andExpect(jsonPath("$.admin.name").value("ผู้ดูแลแพลตฟอร์ม"))
        .andReturn().getResponse().getContentAsString();
    return JSON.readTree(body).path("token").asString();
  }

  @Test
  void alwaysAsksTheAuthenticatorCode() throws Exception {
    var first = passwordStep("platform", "platform");
    assertThat(first.path("mfa").asString()).isEqualTo("setup");
    String secret = first.path("setup").path("secret").asString();
    assertThat(first.path("setup").path("otpauthUrl").asString())
        .isEqualTo("otpauth://totp/AN22%3APLATFORM%3Aplatform?secret=" + secret + "&issuer=AN22&algorithm=SHA1&digits=6&period=30");
    // ลองใหม่ได้ secret เดิม
    assertThat(passwordStep("platform", "platform").path("setup").path("secret").asString()).isEqualTo(secret);
    String challenge = first.path("challenge").asString();
    // challenge ใช้แทน token ไม่ได้
    mvc.perform(get("/platform/me").header("Authorization", bearer(challenge))).andExpect(status().isUnauthorized());
    codeStep(null, "123456").andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"หมดเวลายืนยันรหัส กรุณาเข้าสู่ระบบใหม่\"}", true));
    codeStep(login("DEMO", "admin", "admin"), "123456").andExpect(status().isBadRequest());
    codeStep(challenge, "000000").andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"รหัสยืนยันไม่ถูกต้อง\"}", true));
    long step = TotpRules.step(System.currentTimeMillis());
    codeStep(challenge, TotpRules.code(secret, step)).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());

    var again = passwordStep("platform", "platform");
    assertThat(again.path("mfa").asString()).isEqualTo("verify");
    assertThat(again.has("setup")).isFalse();
    String next = again.path("challenge").asString();
    codeStep(next, TotpRules.code(secret, step)).andExpect(status().isBadRequest()); // ใช้แล้ว
    codeStep(next, TotpRules.code(secret, step + 1)).andExpect(status().isOk());
  }

  @Test
  void loginAndMe() throws Exception {
    String token = platformLogin("Platform", "platform");
    mvc.perform(get("/platform/me").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"id\":1,\"username\":\"platform\",\"name\":\"ผู้ดูแลแพลตฟอร์ม\"}", true));
  }

  @Test
  void wrongLoginIs401WithOneMessage() throws Exception {
    for (String[] wrong : new String[][] {{"platform", "x"}, {"nobody", "platform"}, {"admin", "admin"}}) {
      mvc.perform(post("/platform/auth/login").header("X-Real-IP", "198.18.0.2").contentType(MediaType.APPLICATION_JSON)
              .content(JSON.writeValueAsString(Map.of("username", wrong[0], "password", wrong[1]))))
          .andExpect(status().isUnauthorized())
          .andExpect(content().json("{\"message\":\"ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง\"}", true));
    }
  }

  @Test
  void tokensDoNotCrossBetweenShopAndPlatform() throws Exception {
    String shop = login("DEMO", "admin", "admin");
    mvc.perform(get("/platform/me").header("Authorization", bearer(shop)))
        .andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    mvc.perform(get("/platform/me")).andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    // admin ที่ไม่มีแล้ว
    mvc.perform(get("/platform/me").header("Authorization", bearer(jwt.issuePlatform(999_999))))
        .andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    String platform = platformLogin("platform", "platform");
    mvc.perform(get("/me").header("Authorization", bearer(platform))).andExpect(status().isUnauthorized());
  }
}
