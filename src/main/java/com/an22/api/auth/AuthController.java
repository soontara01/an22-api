package com.an22.api.auth;

import com.an22.api.auth.AuthService.LoginRequest;
import com.an22.api.auth.AuthService.MfaVerifyRequest;
import com.an22.api.auth.AuthService.VerifyRequest;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.LoginResult;
import com.an22.api.model.Session;
import com.an22.api.model.SignupPayload;
import com.an22.api.model.User;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** login / session ของร้าน (api-contract.md หัวข้อ Auth) — logout เป็นของ an22-web (E48). */
@RestController
public class AuthController {

  public record VerifyResponse(User user) {}

  private final AuthService auth;
  private final SessionService sessions;
  private final AccountService accounts;

  AuthController(AuthService auth, SessionService sessions, AccountService accounts) {
    this.auth = auth;
    this.sessions = sessions;
    this.accounts = accounts;
  }

  /** ไม่ต้องมี token (public chain) — ผิด → 401 ข้อความเดียว, ผิดบ่อย → 429. */
  @PostMapping("/auth/login")
  LoginResult login(@RequestBody LoginRequest body, HttpServletRequest request) {
    return auth.login(body, ClientIp.of(request));
  }

  /** ขั้นรหัส Microsoft Authenticator (E55, public chain — Bearer = challenge ที่ an22-web เก็บไว้) — ผิด / หมดเวลา → 400 เสมอ. */
  @PostMapping("/auth/mfa/verify")
  LoginResponse verifyMfa(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) MfaVerifyRequest body,
      HttpServletRequest request) {
    String challenge = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).strip() : null;
    return auth.verifyMfa(challenge, body, ClientIp.of(request));
  }

  /** สมัครใช้งาน (public) — สำเร็จ = login เป็น owner ทันที; ถี่เกิน → 429. */
  @PostMapping("/auth/signup")
  LoginResponse signup(@RequestBody SignupPayload body, HttpServletRequest request) {
    return accounts.signup(body, ClientIp.of(request));
  }

  /** ลืมรหัสผ่าน owner (public) — ตอบข้อความเดียวเสมอ; ถี่เกิน → 429. */
  @PostMapping("/auth/forgot-password")
  AccountService.Message forgotPassword(@RequestBody AccountService.ForgotPasswordRequest body, HttpServletRequest request) {
    return accounts.forgotPassword(body, ClientIp.of(request));
  }

  /** ตั้งรหัสผ่านด้วยลิงก์ทางอีเมล (public) — ตอบ body ว่าง. */
  @PostMapping("/auth/reset-password")
  void resetPassword(@RequestBody AccountService.ResetPasswordRequest body) {
    accounts.resetPassword(body);
  }

  /** ตรวจรหัสในบริษัทของ token โดยไม่สร้าง session — ผิดทุกกรณี 400. */
  @PostMapping("/auth/verify")
  VerifyResponse verify(ShopSession session, @RequestBody VerifyRequest body, HttpServletRequest request) {
    return new VerifyResponse(auth.verify(session, body, ClientIp.of(request)));
  }

  /** session ปัจจุบัน — ไม่ตรวจ X-Branch-Id (สาขาที่เครื่องจำไว้อาจหมดสิทธิ์แล้ว). */
  @GetMapping("/me")
  Session me(ShopSession session) {
    return sessions.me(session);
  }
}
