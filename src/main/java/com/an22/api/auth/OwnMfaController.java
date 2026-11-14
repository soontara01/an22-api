package com.an22.api.auth;

import com.an22.api.model.MfaSetup;
import com.an22.api.model.User;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Microsoft Authenticator ของผู้ใช้เอง (E55) — ทุก role; ปิดใช้ด้วย POST เพราะมี body (รหัสผ่าน). */
@RestController
public class OwnMfaController {

  public record CodeBody(String code) {}

  public record PasswordBody(String password) {}

  private final OwnMfaService service;

  OwnMfaController(OwnMfaService service) {
    this.service = service;
  }

  @PostMapping("/me/mfa/setup")
  MfaSetup setup(ShopSession s) {
    return service.setup(s);
  }

  @PostMapping("/me/mfa/confirm")
  User confirm(ShopSession s, @RequestBody CodeBody body) {
    return service.confirm(s, body.code());
  }

  @PostMapping("/me/mfa/disable")
  User disable(ShopSession s, @RequestBody PasswordBody body, HttpServletRequest request) {
    return service.disable(s, body.password(), ClientIp.of(request));
  }
}
