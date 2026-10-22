package com.an22.api.company;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.User;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /company/*} — บัญชีบริษัท (owner, E33). */
@RestController
public class CompanyController {

  public record OwnerTransfer(Long userId, String password) {}

  private final CompanyService service;

  CompanyController(CompanyService service) {
    this.service = service;
  }

  @PostMapping("/company/owner")
  List<User> transferOwner(ShopSession s, @RequestBody OwnerTransfer body) {
    return service.transferOwner(s, body.userId(), body.password());
  }
}
