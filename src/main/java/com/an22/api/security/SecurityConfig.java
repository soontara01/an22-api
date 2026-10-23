package com.an22.api.security;

import com.an22.api.auth.AuthService;
import com.an22.api.auth.SessionService;
import com.an22.api.platform.PlatformAuthService;
import com.an22.api.web.ErrorHandler;
import com.an22.api.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * api เป็น JWT stateless หลัง an22-web (BFF, E48): ไม่มี session / cookie / CSRF / CORS / logout. 3 chain:
 *
 * <ol>
 *   <li>public — health, {@code POST /auth/login}, {@code POST /platform/auth/login}: ไม่อ่าน token
 *   <li>{@code /platform/**} — token {@code aud = platform} + {@link PlatformSessionFilter}; ไม่ผ่าน → 401 ข้อความของ platform
 *   <li>ที่เหลือ (ร้าน) — token {@code aud = shop} + {@link ShopSessionFilter}; ไม่ผ่าน → 401 (ยกเว้น {@code auth/verify} → 400)
 * </ol>
 *
 * token ของร้านใช้กับ platform ไม่ได้และกลับกัน (aud ต่างกัน → 401).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** path ที่ไม่ต้องมี token (ไม่รวม /api). */
  static final String[] PUBLIC_PATHS = {"/healthz", "/readyz", "/error", "/auth/login", "/auth/signup", "/auth/forgot-password", "/auth/reset-password",
      "/platform/auth/login"};

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  @Order(1)
  SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
    stateless(http).securityMatcher(PUBLIC_PATHS).authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain platformChain(
      HttpSecurity http,
      JwtService jwt,
      PlatformAuthService platform,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      JsonMapper json)
      throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (request, response, ex) ->
            write(json, response, HttpServletResponse.SC_UNAUTHORIZED, PlatformSessionFilter.SESSION_EXPIRED);
    stateless(http)
        .securityMatcher("/platform/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwt.platformDecoder())).authenticationEntryPoint(unauthenticated))
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated))
        .addFilterAfter(new PlatformSessionFilter(platform, errors), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain shopChain(
      HttpSecurity http,
      JwtService jwt,
      SessionService sessions,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      JsonMapper json)
      throws Exception {
    AuthenticationEntryPoint unauthenticated = (request, response, ex) -> shopUnauthenticated(json, request, response);
    JwtDecoder decoder = jwt.shopDecoder();
    stateless(http)
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)).authenticationEntryPoint(unauthenticated))
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated))
        .addFilterAfter(new ShopSessionFilter(sessions, errors), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }

  /** ไม่มี / ไม่รู้จัก token: 401 — {@code auth/verify} ตอบ 400 เสมอ (ห้าม 401: หน้าบ้านจะ logout คนหน้าเครื่อง POS). */
  private static void shopUnauthenticated(JsonMapper json, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if ("/auth/verify".equals(ErrorHandler.apiPath(request))) {
      write(json, response, HttpServletResponse.SC_BAD_REQUEST, AuthService.WRONG_LOGIN);
    } else {
      write(json, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorResponse.SESSION_EXPIRED);
    }
  }

  private static void write(JsonMapper json, HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), new ErrorResponse(message));
  }
}
