package com.an22.api.security;

import com.an22.api.web.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * api เป็น JWT stateless หลัง an22-web (BFF, E48): ไม่มี session / cookie / CSRF / CORS / logout.
 *
 * <p>ขั้น 0: ยังไม่มี JWT (ขั้น 2) — เปิดเฉพาะ health, ที่เหลือ 401 ทั้งหมด.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiChain(HttpSecurity http, JsonMapper json) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/healthz", "/readyz", "/error").permitAll().anyRequest().authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) ->
                            write(json, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorResponse.SESSION_EXPIRED))
                    // ขั้น 0 ยังไม่มี role — ข้อความ 403 ของแต่ละ endpoint มาจาก ApiException ตาม mock
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            write(json, response, HttpServletResponse.SC_FORBIDDEN, "ไม่มีสิทธิ์ทำรายการนี้")));
    return http.build();
  }

  private static void write(JsonMapper json, HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), new ErrorResponse(message));
  }
}
