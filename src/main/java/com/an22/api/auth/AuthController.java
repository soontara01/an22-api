package com.an22.api.auth;

import com.an22.api.auth.AuthService.LoginRequest;
import com.an22.api.auth.AuthService.VerifyRequest;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.Session;
import com.an22.api.model.User;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** login / session ของร้าน (api-contract.md หัวข้อ Auth) — logout เป็นของ an22-web (E48). */
@RestController
public class AuthController {

  public record VerifyResponse(User user) {}

  private final AuthService auth;
  private final SessionService sessions;

  AuthController(AuthService auth, SessionService sessions) {
    this.auth = auth;
    this.sessions = sessions;
  }

  /** ไม่ต้องมี token (public chain) — ผิด → 401 ข้อความเดียว, ผิดบ่อย → 429. */
  @PostMapping("/auth/login")
  LoginResponse login(@RequestBody LoginRequest body, HttpServletRequest request) {
    return auth.login(body, ClientIp.of(request));
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
