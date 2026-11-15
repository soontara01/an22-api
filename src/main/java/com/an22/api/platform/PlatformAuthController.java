package com.an22.api.platform;

import com.an22.api.model.MfaChallenge;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformLoginResponse;
import com.an22.api.platform.PlatformAuthService.LoginRequest;
import com.an22.api.platform.PlatformAuthService.MfaVerifyRequest;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** login / session ของ platform admin (E36). */
@RestController
public class PlatformAuthController {

  private final PlatformAuthService service;

  PlatformAuthController(PlatformAuthService service) {
    this.service = service;
  }

  /** รหัสผ่านถูก → ขั้นรหัส Authenticator เสมอ (E56). */
  @PostMapping("/platform/auth/login")
  MfaChallenge login(@RequestBody LoginRequest body, HttpServletRequest request) {
    return service.login(body, ClientIp.of(request));
  }

  /** ขั้นรหัส (public chain — Bearer = challenge ที่ an22-web เก็บไว้) — ผิด / หมดเวลา → 400. */
  @PostMapping("/platform/auth/mfa/verify")
  PlatformLoginResponse verifyMfa(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) MfaVerifyRequest body,
      HttpServletRequest request) {
    String challenge = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).strip() : null;
    return service.verifyMfa(challenge, body, ClientIp.of(request));
  }

  /** admin ของ token — PlatformSessionFilter อ่านจาก DB แล้ว. */
  @GetMapping("/platform/me")
  PlatformAdmin me(PlatformAdmin admin) {
    return admin;
  }
}
