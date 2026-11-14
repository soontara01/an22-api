package com.an22.api.auth;

import com.an22.api.model.MfaChange;
import com.an22.api.model.User;
import com.an22.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** เปลี่ยนเครื่อง Microsoft Authenticator ของตัวเอง (E58) — ทุก role. */
@RestController
public class OwnMfaController {

  public record ChangeStart(String password, String code) {}

  public record ChangeConfirm(String change, String code) {}

  private final OwnMfaService service;

  OwnMfaController(OwnMfaService service) {
    this.service = service;
  }

  @PostMapping("/me/mfa/change")
  MfaChange start(ShopSession s, @RequestBody ChangeStart body, HttpServletRequest request) {
    return service.start(s, body.password(), body.code(), ClientIp.of(request));
  }

  @PostMapping("/me/mfa/change/confirm")
  User confirm(ShopSession s, @RequestBody ChangeConfirm body) {
    return service.confirm(s, body.change(), body.code());
  }
}
