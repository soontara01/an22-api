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
 * B5 (E2E ข้อ 34.7): endpoint ที่นับ {@link LoginThrottle} (REQUIRES_NEW, pool platform) ห้ามถือ connection ของ pool เดียวกันค้างไว้ระหว่างนับ —
 * ยิงพร้อมกันมากกว่าขนาด pool ของ test (5) ต้องตอบครบโดยไม่มี 500 และจบก่อน connectionTimeout ของ pool (30 วิ).
 */
class ThrottlePoolIT extends ApiTest {

  private static final int CONCURRENT = 12;
  private static final Duration LIMIT = Duration.ofSeconds(20);

  @Autowired TestCompanies companies;

  /** ยิง {@code request(i)} พร้อมกัน → status ทั้งหมด (และต้องจบภายใน {@link #LIMIT}). */
  private List<Integer> concurrently(IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
    try {
      long started = System.nanoTime();
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT; i++) {
        int n = i;
        futures.add(pool.submit(() -> mvc.perform(request.apply(n)).andReturn().getResponse().getStatus()));
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

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String ip, Object body) throws Exception {
    return request.header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
  }

  @Test
  void concurrentWrongShopLoginsDoNotExhaustThePlatformPool() throws Exception {
    Company c = companies.create();
    List<Integer> statuses = concurrently(i ->
        unchecked(() -> json(post("/auth/login"), "198.18.34." + i, Map.of("companyCode", c.code(), "username", "staff", "password", "wrong"))));
    assertThat(statuses).allMatch(s -> s == 401 || s == 429);
  }

  @Test
  void concurrentRightShopLoginsAllSucceed() throws Exception {
    Company c = companies.create();
    List<Integer> statuses = concurrently(i ->
        unchecked(() -> json(post("/auth/login"), "198.18.35." + i, Map.of("companyCode", c.code(), "username", "boss", "password", TestCompanies.PASSWORD))));
    assertThat(statuses).containsOnly(200);
  }

  @Test
  void concurrentWrongPlatformLoginsDoNotExhaustThePlatformPool() throws Exception {
    List<Integer> statuses = concurrently(i ->
        unchecked(() -> json(post("/platform/auth/login"), "198.18.36." + i, Map.of("username", "nobody" + i, "password", "wrong"))));
    assertThat(statuses).containsOnly(401);
  }

  @Test
  void concurrentForgotPasswordAndSignupDoNotExhaustThePlatformPool() throws Exception {
    Company c = companies.create();
    List<Integer> forgot = concurrently(i ->
        unchecked(() -> json(post("/auth/forgot-password"), "198.18.37." + i, Map.of("companyCode", c.code(), "email", "x" + i + "@example.test"))));
    assertThat(forgot).containsOnly(200);
    List<Integer> signup = concurrently(i ->
        unchecked(() -> json(post("/auth/signup"), "198.18.38." + i, Map.of("companyCode", "", "companyName", ""))));
    assertThat(signup).containsOnly(400);
  }

  private interface Builder {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  private static MockHttpServletRequestBuilder unchecked(Builder builder) {
    try {
      return builder.build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
