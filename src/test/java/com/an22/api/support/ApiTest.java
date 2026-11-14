package com.an22.api.support;

import com.an22.api.config.ClockConfig;
import com.an22.api.rules.TotpRules;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** base ของ integration test: แอปเต็ม + MockMvc บน {@link TestDatabase} (ผ่าน PgBouncer). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTest {

  @Autowired protected MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.register(registry);
  }

  protected static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private TenantSql tenantSql;

  /**
   * login ร้านครบสองขั้น → token (ไม่ผ่าน → test ล้ม). ทุก login ต้องผ่านรหัส Microsoft Authenticator (E57) — ดู {@link #passBothSteps}.
   */
  protected String login(String companyCode, String username, String password) throws Exception {
    String verified = passBothSteps(companyCode, username, password, null).andReturn().getResponse().getContentAsString();
    JsonNode token = JSON.readTree(verified).path("token");
    if (!token.isString()) {
      throw new AssertionError("รหัส Authenticator ไม่ผ่าน: " + verified);
    }
    return token.asString();
  }

  /**
   * รหัสผ่าน แล้วรหัส 6 หลัก → ผลของ {@code POST /auth/mfa/verify} (session + token). ล้าง Authenticator ของผู้ใช้ก่อนจึงได้ setup + secret ทุกครั้ง
   * (รหัสของช่วงเดียวกันใช้ซ้ำไม่ได้ — login คนเดิมหลายครั้งใน 30 วิ ของ test จะชนกัน). รหัสผ่านไม่ผ่าน → AssertionError พร้อมคำตอบ.
   *
   * @param ip {@code X-Real-IP} (null = ไม่ส่ง)
   */
  protected ResultActions passBothSteps(String companyCode, String username, String password, String ip) throws Exception {
    tenantSql.platform(jdbc -> jdbc.sql(
            "DELETE FROM dt_user_mfa m USING dt_users u, dt_companies c WHERE m.tenant_id = u.tenant_id AND m.user_id = u.id"
                + " AND u.tenant_id = c.id AND c.code = upper(btrim(:c)) AND u.username = lower(btrim(:u))")
        .param("c", companyCode).param("u", username).update());
    var step1 = post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("companyCode", companyCode, "username", username, "password", password)));
    if (ip != null) {
      step1.header("X-Real-IP", ip);
    }
    String body = mvc.perform(step1).andReturn().getResponse().getContentAsString();
    JsonNode challenge = JSON.readTree(body);
    String secret = challenge.path("setup").path("secret").asString("");
    if (!challenge.path("challenge").isString() || secret.isEmpty()) {
      throw new AssertionError("login ไม่ผ่าน: " + body);
    }
    var step2 = post("/auth/mfa/verify")
        .header("Authorization", bearer(challenge.path("challenge").asString()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("code", TotpRules.code(secret, TotpRules.step(System.currentTimeMillis())))));
    if (ip != null) {
      step2.header("X-Real-IP", ip);
    }
    return mvc.perform(step2);
  }

  protected static String bearer(String token) {
    return "Bearer " + token;
  }

  /** path ไม่รวม {@code /api} (เหมือน api-contract.md). */
  protected static MockHttpServletRequestBuilder get(String path) {
    return MockMvcRequestBuilders.get("/api" + path).contextPath("/api");
  }

  protected static MockHttpServletRequestBuilder post(String path) {
    return MockMvcRequestBuilders.post("/api" + path).contextPath("/api");
  }

  protected static MockHttpServletRequestBuilder put(String path) {
    return MockMvcRequestBuilders.put("/api" + path).contextPath("/api");
  }

  /** request ของผู้ใช้ร้าน (+ body JSON ถ้ามี). */
  protected static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request, Object body) {
    request.header("Authorization", bearer(token));
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    return request;
  }

  protected static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
    return as(token, request, null);
  }

  /** {@code {"message": "…"}}. */
  protected static String message(String text) {
    return JSON.writeValueAsString(Map.of("message", text));
  }

  /** รายการตามวันที่ต้องมีช่วง ≤ 366 วัน (ListLimits): 366 วันล่าสุดถึงวันนี้ (Asia/Bangkok). */
  protected static MockHttpServletRequestBuilder lastYear(MockHttpServletRequestBuilder request) {
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    return request.param("from", today.minusDays(365).toString()).param("to", today.toString());
  }

  protected static MockHttpServletRequestBuilder delete(String path) {
    return MockMvcRequestBuilders.delete("/api" + path).contextPath("/api");
  }
}
