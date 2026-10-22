package com.an22.api.support;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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

  /** login ร้าน → token (ไม่ผ่าน → test ล้ม). */
  protected String login(String companyCode, String username, String password) throws Exception {
    String body =
        mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("companyCode", companyCode, "username", username, "password", password))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode token = JSON.readTree(body).path("token");
    if (!token.isString()) {
      throw new AssertionError("login ไม่ผ่าน: " + body);
    }
    return token.asString();
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

  protected static MockHttpServletRequestBuilder delete(String path) {
    return MockMvcRequestBuilders.delete("/api" + path).contextPath("/api");
  }
}
