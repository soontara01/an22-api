package com.an22.api.branch;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /branches} — ทุกสาขาของบริษัท (รวมที่ปิด); เขียน: admin บริษัท. */
@RestController
public class BranchController {

  static final String ADMIN_ONLY = "จัดการสาขาได้เฉพาะผู้ดูแลระบบ";

  private final BranchService service;

  BranchController(BranchService service) {
    this.service = service;
  }

  @GetMapping("/branches")
  List<Branch> list() {
    return service.list();
  }

  @GetMapping("/branches/{id}")
  Branch get(@PathVariable long id) {
    return service.get(id);
  }

  @PostMapping("/branches")
  Branch create(ShopSession s, @RequestBody BranchPayload body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    Permissions.requireCompanyAdmin(s);
    return service.create(s, body);
  }

  @PutMapping("/branches/{id}")
  Branch update(ShopSession s, @PathVariable long id, @RequestBody BranchPayload body) {
    Permissions.requireAdmin(s, ADMIN_ONLY);
    Permissions.requireCompanyAdmin(s);
    return service.update(s, id, body);
  }
}
