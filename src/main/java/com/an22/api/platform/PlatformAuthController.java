package com.an22.api.platform;

import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformLoginResponse;
import com.an22.api.platform.PlatformAuthService.LoginRequest;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** login / session ของ platform admin (E36). */
@RestController
public class PlatformAuthController {

  private final PlatformAuthService service;

  PlatformAuthController(PlatformAuthService service) {
    this.service = service;
  }

  @PostMapping("/platform/auth/login")
  PlatformLoginResponse login(@RequestBody LoginRequest body, HttpServletRequest request) {
    return service.login(body, ClientIp.of(request));
  }

  /** admin ของ token — PlatformSessionFilter อ่านจาก DB แล้ว. */
  @GetMapping("/platform/me")
  PlatformAdmin me(PlatformAdmin admin) {
    return admin;
  }
}
