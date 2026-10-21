package com.an22.api.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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

  /** path ไม่รวม {@code /api} (เหมือน api-contract.md). */
  protected static MockHttpServletRequestBuilder get(String path) {
    return MockMvcRequestBuilders.get("/api" + path).contextPath("/api");
  }

  protected static MockHttpServletRequestBuilder post(String path) {
    return MockMvcRequestBuilders.post("/api" + path).contextPath("/api");
  }

  protected static MockHttpServletRequestBuilder delete(String path) {
    return MockMvcRequestBuilders.delete("/api" + path).contextPath("/api");
  }
}
