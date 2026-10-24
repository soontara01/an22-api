package com.an22.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ยิงพร้อมกัน (E2E ข้อ 34.7):
 * <ul>
 *   <li>B5 — endpoint ที่นับ {@link LoginThrottle} (REQUIRES_NEW, pool platform) ห้ามถือ connection ของ pool เดียวกันค้างไว้ระหว่างนับ: ยิงมากกว่า
 *       ขนาด pool ของ test (5) ต้องตอบครบโดยไม่มี 500 และจบก่อน connectionTimeout ของ pool (30 วิ)
 *   <li>B6 — burst พร้อมกันลองได้ไม่เกินเพดาน (จองสิทธิ์ก่อนตรวจรหัส ไม่ใช่ check แล้วค่อยนับ)
 * </ul>
 */
class ThrottleConcurrencyIT extends ApiTest {

  private static final int CONCURRENT = 12;
  private static final Duration LIMIT = Duration.ofSeconds(20);

  @Autowired TestCompanies companies;

  /** ยิง {@code request(i)} พร้อมกัน {@code n} ตัว → status ทั้งหมด (และต้องจบภายใน {@link #LIMIT}). */
  private List<Integer> concurrently(int n, IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      long started = System.nanoTime();
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int k = i;
        futures.add(pool.submit(() -> mvc.perform(request.apply(k)).andReturn().getResponse().getStatus()));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : futures) {
        statuses.add(f.get(LIMIT.toSeconds() + 15, TimeUnit.SECONDS));
      }
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LIMIT);
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  private List<Integer> concurrently(IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
    return concurrently(CONCURRENT, request);
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String ip, Object body) {
    try {
      return request.header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static long count(List<Integer> statuses, int status) {
    return statuses.stream().filter(s -> s == status).count();
  }

  // ---------- B5: pool ไม่ติดตาย ----------

  @Test
  void concurrentWrongShopLoginsDoNotExhaustThePlatformPool() throws Exception {
    Company c = companies.create();
    List<Integer> statuses = concurrently(i ->
        json(post("/auth/login"), "198.18.34." + i, Map.of("companyCode", c.code(), "username", "staff", "password", "wrong")));
    assertThat(statuses).allMatch(s -> s == 401 || s == 429);
  }

  @Test
  void concurrentRightShopLoginsAllSucceed() throws Exception {
    Company c = companies.create();
    // 3 บัญชี × 4 พร้อมกัน — ไม่เกินเพดานต่อบัญชี (สิทธิ์ที่จองระหว่างตรวจรหัสนับรวมด้วย: บัญชีเดียวพร้อมกัน > 5 ได้ 429 ชั่วคราว — B6)
    String[] users = {"owner", "boss", "staff"};
    List<Integer> statuses = concurrently(i -> json(post("/auth/login"), "198.18.35." + i,
        Map.of("companyCode", c.code(), "username", users[i % users.length], "password", TestCompanies.PASSWORD)));
    assertThat(statuses).containsOnly(200);
  }

  @Test
  void concurrentWrongPlatformLoginsDoNotExhaustThePlatformPool() throws Exception {
    List<Integer> statuses = concurrently(i ->
        json(post("/platform/auth/login"), "198.18.36." + i, Map.of("username", "nobody" + i, "password", "wrong")));
    assertThat(statuses).containsOnly(401);
  }

  @Test
  void concurrentForgotPasswordAndSignupDoNotExhaustThePlatformPool() throws Exception {
    Company c = companies.create();
    List<Integer> forgot = concurrently(i ->
        json(post("/auth/forgot-password"), "198.18.37." + i, Map.of("companyCode", c.code(), "email", "x" + i + "@example.test")));
    assertThat(forgot).containsOnly(200);
    List<Integer> signup = concurrently(i -> json(post("/auth/signup"), "198.18.38." + i, Map.of("companyCode", "", "companyName", "")));
    assertThat(signup).containsOnly(400);
  }

  // ---------- B6: burst ไม่เกินเพดาน ----------

  @Test
  void burstOfWrongLoginsTriesAtMostTheAccountLimit() throws Exception {
    Company c = companies.create();
    List<Integer> statuses = concurrently(20, i ->
        json(post("/auth/login"), "198.18.39." + i, Map.of("companyCode", c.code(), "username", "staff", "password", "wrong" + i)));
    assertThat(count(statuses, 401)).isEqualTo(LoginThrottle.ACCOUNT_MAX);
    assertThat(count(statuses, 429)).isEqualTo(20 - LoginThrottle.ACCOUNT_MAX);
    // ล็อกแล้ว: รหัสถูกก็ไม่ผ่าน
    assertThat(mvc.perform(json(post("/auth/login"), "198.18.39.200",
        Map.of("companyCode", c.code(), "username", "staff", "password", TestCompanies.PASSWORD))).andReturn().getResponse().getStatus())
        .isEqualTo(429);
  }

  @Test
  void burstOfWrongVerifiesTriesAtMostTheAccountLimit() throws Exception {
    Company c = companies.create();
    String token = login(c.code(), "staff", TestCompanies.PASSWORD);
    List<Integer> statuses = concurrently(10, i ->
        json(as(token, post("/auth/verify")), "198.18.40." + i, Map.of("username", "boss", "password", "wrong" + i)));
    assertThat(count(statuses, 400)).isEqualTo(LoginThrottle.ACCOUNT_MAX);
    assertThat(count(statuses, 429)).isEqualTo(10 - LoginThrottle.ACCOUNT_MAX);
  }

  @Test
  void burstOfSignupsFromOneIpStopsAtTheLimit() throws Exception {
    List<Integer> statuses = concurrently(15, i -> json(post("/auth/signup"), "198.18.41.1", Map.of("companyCode", "", "companyName", "")));
    assertThat(count(statuses, 400)).isEqualTo(AccountService.SIGNUP_IP_MAX);
    assertThat(count(statuses, 429)).isEqualTo(15 - AccountService.SIGNUP_IP_MAX);
  }

  @Test
  void rightPasswordOfASuspendedCompanyIsNotCountedAsAFailure() throws Exception {
    Company c = companies.create();
    companies.suspend(c, "platform");
    for (int i = 0; i < LoginThrottle.ACCOUNT_MAX + 2; i++) {
      assertThat(mvc.perform(json(post("/auth/login"), "198.18.42.1",
          Map.of("companyCode", c.code(), "username", "boss", "password", TestCompanies.PASSWORD))).andReturn().getResponse().getStatus())
          .isEqualTo(401);
    }
  }
}
