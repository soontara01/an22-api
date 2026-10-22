package com.an22.api.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** จำกัด login ผิด (ตกลง 2026-10-22): บัญชี 5 ครั้ง / 15 นาที, IP 30 ครั้ง / 15 นาที → 429. */
class LoginThrottleIT extends ApiTest {

  private static final String TOO_MANY = "{\"message\":\"พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่\"}";

  @Autowired TestCompanies companies;

  private ResultActions attempt(String ip, String code, String username, String password) throws Exception {
    return mvc.perform(post("/auth/login")
        .header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("companyCode", code, "username", username, "password", password))));
  }

  @Test
  void accountLocksAfterFiveFailuresEvenFromAnotherIpAndWithTheRightPassword() throws Exception {
    Company c = companies.create();
    for (int i = 0; i < 5; i++) {
      attempt("203.0.113.1", c.code(), "staff", "wrong").andExpect(status().isUnauthorized());
    }
    attempt("203.0.113.1", c.code(), "staff", TestCompanies.PASSWORD).andExpect(status().isTooManyRequests()).andExpect(content().json(TOO_MANY, true));
    attempt("203.0.113.2", c.code(), "staff", TestCompanies.PASSWORD).andExpect(status().isTooManyRequests());
    // บัญชีอื่นของบริษัทเดียวกันไม่โดน
    attempt("203.0.113.2", c.code(), "boss", TestCompanies.PASSWORD).andExpect(status().isOk());
  }

  @Test
  void unknownUsernamesAreCountedTooWithoutTellingTheyDoNotExist() throws Exception {
    Company c = companies.create();
    for (int i = 0; i < 5; i++) {
      attempt("203.0.113.3", c.code(), "ghost", "x").andExpect(status().isUnauthorized());
    }
    attempt("203.0.113.3", c.code(), "ghost", "x").andExpect(status().isTooManyRequests());
  }

  @Test
  void successResetsTheAccountCounter() throws Exception {
    Company c = companies.create();
    for (int i = 0; i < 4; i++) {
      attempt("203.0.113.4", c.code(), "boss", "wrong").andExpect(status().isUnauthorized());
    }
    attempt("203.0.113.4", c.code(), "boss", TestCompanies.PASSWORD).andExpect(status().isOk());
    for (int i = 0; i < 4; i++) {
      attempt("203.0.113.4", c.code(), "boss", "wrong").andExpect(status().isUnauthorized());
    }
    attempt("203.0.113.4", c.code(), "boss", TestCompanies.PASSWORD).andExpect(status().isOk());
  }

  @Test
  void ipLocksAfterThirtyFailuresAcrossAccounts() throws Exception {
    Company c = companies.create();
    for (int i = 0; i < 30; i++) {
      attempt("203.0.113.5", c.code(), "user" + i, "wrong").andExpect(status().isUnauthorized());
    }
    attempt("203.0.113.5", c.code(), "owner", TestCompanies.PASSWORD).andExpect(status().isTooManyRequests());
    attempt("203.0.113.6", c.code(), "owner", TestCompanies.PASSWORD).andExpect(status().isOk());
  }

  @Test
  void verifyCountsAgainstTheSameAccount() throws Exception {
    Company c = companies.create();
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/auth/verify").header("Authorization", bearer(staff)).header("X-Real-IP", "203.0.113.7")
              .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"boss\",\"password\":\"x\"}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post("/auth/verify").header("Authorization", bearer(staff)).header("X-Real-IP", "203.0.113.7")
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"boss\",\"password\":\"" + TestCompanies.PASSWORD + "\"}"))
        .andExpect(status().isTooManyRequests());
    // login ของบัญชีเดียวกันก็ถูกล็อก
    attempt("203.0.113.8", c.code(), "boss", TestCompanies.PASSWORD).andExpect(status().isTooManyRequests());
  }
}
