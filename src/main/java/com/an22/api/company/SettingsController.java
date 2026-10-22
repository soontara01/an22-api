package com.an22.api.company;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.MenuAccess;
import com.an22.api.model.StoreInfo;
import com.an22.api.web.BranchContext;
import com.an22.api.web.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /settings/store|company|branch|menu-access} — อ่านได้ทุกคน, เขียนตามสิทธิ์ (api-contract.md หัวข้อ Auth / settings). */
@RestController
public class SettingsController {

  static final String STORE_ADMIN_ONLY = "แก้ข้อมูลร้านได้เฉพาะผู้ดูแลระบบ";
  static final String MENU_ADMIN_ONLY = "กำหนดสิทธิ์เมนูได้เฉพาะผู้ดูแลระบบ";

  private final SettingsService service;

  SettingsController(SettingsService service) {
    this.service = service;
  }

  @GetMapping("/settings/store")
  StoreInfo store(BranchContext branch, @RequestParam(required = false) String branchId) {
    return service.storeInfo(branch, branchId);
  }

  @PutMapping("/settings/store")
  StoreInfo updateStore(ShopSession s, BranchContext branch, @RequestBody StoreInfo body) {
    Permissions.requireAdmin(s, STORE_ADMIN_ONLY);
    Permissions.requireCompanyAdmin(s);
    return service.updateStoreInfo(s, branch, body);
  }

  @GetMapping("/settings/company")
  CompanyInfo company() {
    return service.companyInfo();
  }

  @PutMapping("/settings/company")
  CompanyInfo updateCompany(ShopSession s, @RequestBody CompanyInfo body) {
    Permissions.requireAdmin(s, STORE_ADMIN_ONLY);
    Permissions.requireCompanyAdmin(s);
    return service.updateCompanyInfo(s, body);
  }

  @GetMapping("/settings/branch")
  Branch branch(BranchContext branch) {
    return service.branchSettings(branch);
  }

  @PutMapping("/settings/branch")
  Branch updateBranch(ShopSession s, BranchContext branch, @RequestBody BranchPayload body) {
    Permissions.requireAdmin(s, STORE_ADMIN_ONLY);
    return service.updateBranchSettings(s, branch, body);
  }

  @GetMapping("/settings/menu-access")
  MenuAccess menuAccess() {
    return service.menuAccess();
  }

  @PutMapping("/settings/menu-access")
  MenuAccess updateMenuAccess(ShopSession s, @RequestBody MenuAccess body) {
    Permissions.requireAdmin(s, MENU_ADMIN_ONLY);
    return service.updateMenuAccess(s, body);
  }
}
