package com.an22.api.pos;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.PosTerminal;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos-terminals} — ทุกเครื่องของบริษัท (เรียงเลขเครื่อง); เขียน: admin. */
@RestController
public class PosTerminalController {

  static final String ADMIN_ONLY = "แก้ทะเบียนเครื่อง POS ได้เฉพาะผู้ดูแลระบบ";

  private final PosTerminalService service;

  PosTerminalController(PosTerminalService service) {
    this.service = service;
  }

  @GetMapping("/pos-terminals")
  List<PosTerminal> list() {
    return service.list();
  }

  @PostMapping("/pos-terminals")
  PosTerminal create(ShopSession s, @RequestBody PosTerminal body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.create(s, body);
  }

  @PutMapping("/pos-terminals/{id}")
  PosTerminal update(ShopSession s, @PathVariable int id, @RequestBody PosTerminal body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    return service.update(s, id, body);
  }

  @DeleteMapping("/pos-terminals/{id}")
  void delete(ShopSession s, @PathVariable int id) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    service.delete(s, id);
  }
}
