package com.an22.api.user;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.User;
import com.an22.api.model.UserPayload;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /users} — admin เท่านั้น (อื่น 403). */
@RestController
public class UserController {

  static final String ADMIN_ONLY = "จัดการผู้ใช้ได้เฉพาะผู้ดูแลระบบ";

  public record PasswordBody(String password) {}

  private final UserService service;

  UserController(UserService service) {
    this.service = service;
  }

  @GetMapping("/users")
  List<User> list(ShopSession s) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.list();
  }

  @GetMapping("/users/{id}")
  User get(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.get(id);
  }

  @PostMapping("/users")
  User create(ShopSession s, @RequestBody UserPayload body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.create(s, body);
  }

  @PutMapping("/users/{id}")
  User update(ShopSession s, @PathVariable long id, @RequestBody UserPayload body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.update(s, id, body);
  }

  @DeleteMapping("/users/{id}")
  void delete(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    service.delete(s, id);
  }

  @PutMapping("/users/{id}/password")
  void setPassword(ShopSession s, @PathVariable long id, @RequestBody PasswordBody body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    service.setPassword(s, id, body.password());
  }
}
