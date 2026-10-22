package com.an22.api.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.security.JwtService;
import com.an22.api.support.ApiTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** login / session ของ platform admin (E36) — token แยกจากร้าน. */
class PlatformAuthIT extends ApiTest {

  private static final String EXPIRED = "{\"message\":\"เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่\"}";

  @Autowired JwtService jwt;

  private String platformLogin(String username, String password) throws Exception {
    String body = mvc.perform(post("/platform/auth/login").header("X-Real-IP", "198.18.0.1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin.username").value("platform"))
        .andExpect(jsonPath("$.admin.name").value("ผู้ดูแลแพลตฟอร์ม"))
        .andReturn().getResponse().getContentAsString();
    return JSON.readTree(body).path("token").asString();
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
