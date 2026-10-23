package com.an22.api.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

class ErrorHandlerIT extends ApiTest {

  @Test
  void noTokenIs401WithSessionMessage() throws Exception {
    mvc.perform(get("/sales"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่\"}", true));
  }

  @Test
  @WithMockUser
  void unknownPathIs404LikeMock() throws Exception {
    mvc.perform(get("/no-such/thing"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"message\":\"ไม่มี endpoint GET /no-such/thing\"}", true));
  }

  @Test
  @WithMockUser
  void unsupportedMethodIs404LikeMock() throws Exception {
    mvc.perform(delete("/healthz"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"message\":\"ไม่มี endpoint DELETE /healthz\"}", true));
  }

  @Test
  @WithMockUser
  void unreadableJsonIs400() throws Exception {
    mvc.perform(post("/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content("{\"a\":"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"ข้อมูลที่ส่งมาไม่ถูกต้อง\"}", true));
  }

  @Test
  @WithMockUser
  void apiExceptionKeepsItsStatusAndMessage() throws Exception {
    mvc.perform(post("/test-probe/reject").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"message\":\"ปฏิเสธตามกฎ\"}", true));
  }

  @Test
  @WithMockUser
  void unexpectedErrorIs500WithoutDetails() throws Exception {
    mvc.perform(get("/test-probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().json("{\"message\":\"ระบบขัดข้อง กรุณาลองใหม่\"}", true));
  }

  @Test
  @WithMockUser
  void dbTimeoutIs503() throws Exception {
    mvc.perform(get("/test-probe/slow"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().json("{\"message\":\"ระบบใช้เวลานานเกินไป กรุณาลองใหม่หรือลดช่วงข้อมูล\"}", true));
  }

  @Test
  @WithMockUser
  void jsonKeepsNullsAndWritesIsoDates() throws Exception {
    mvc.perform(get("/test-probe/nulls"))
        .andExpect(status().isOk())
        .andExpect(content().string("{\"empty\":null,\"at\":\"2026-10-21T03:04:05Z\"}"));
  }
}
