package com.an22.api.company;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.CompanyDeletion;
import com.an22.api.model.CompanyExport;
import com.an22.api.model.OnboardingCounts;
import com.an22.api.model.SubscriptionInfo;
import com.an22.api.model.User;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /company/*} — บัญชีบริษัท: แพ็กเกจ / การเก็บเงิน (admin ดู, owner เปลี่ยน), export / ลบ / โอนความเป็นเจ้าของ (owner, E33). */
@RestController
public class CompanyController {

  private static final String DELETION_OWNER_ONLY = "ขอลบข้อมูลบริษัทได้เฉพาะเจ้าของบริษัท";

  public record OwnerTransfer(Long userId, String password) {}

  public record PlanChange(String planId) {}

  private final CompanyService service;
  private final SubscriptionService subscriptions;
  private final CompanyDataService data;

  CompanyController(CompanyService service, SubscriptionService subscriptions, CompanyDataService data) {
    this.service = service;
    this.subscriptions = subscriptions;
    this.data = data;
  }

  @PostMapping("/company/owner")
  List<User> transferOwner(ShopSession s, @RequestBody OwnerTransfer body) {
    return service.transferOwner(s, body.userId(), body.password());
  }

  @GetMapping("/company/subscription")
  SubscriptionInfo subscription(ShopSession s) {
    Permissions.requireAdmin(s, "ดูแพ็กเกจได้เฉพาะผู้ดูแลระบบ");
    return subscriptions.subscription(s);
  }

  @GetMapping("/company/onboarding")
  OnboardingCounts onboarding(ShopSession s) {
    Permissions.requireAdmin(s, "ดูขั้นตอนเริ่มต้นใช้งานได้เฉพาะผู้ดูแลระบบ");
    return subscriptions.onboarding();
  }

  @PutMapping("/company/plan")
  SubscriptionInfo changePlan(ShopSession s, @RequestBody PlanChange body) {
    requireOwner(s, "เปลี่ยนแพ็กเกจได้เฉพาะเจ้าของบริษัท");
    return subscriptions.changePlan(s, body.planId());
  }

  @GetMapping("/company/export")
  CompanyExport export(ShopSession s) {
    requireOwner(s, "ดาวน์โหลดข้อมูลทั้งบริษัทได้เฉพาะเจ้าของบริษัท");
    return data.export(s);
  }

  /** คำขอที่รออยู่ — ไม่มี = body ว่าง. */
  @GetMapping("/company/deletion")
  CompanyDeletion deletion(ShopSession s) {
    requireOwner(s, DELETION_OWNER_ONLY);
    return data.deletion(s);
  }

  @PostMapping("/company/deletion")
  CompanyDeletion requestDeletion(ShopSession s, @RequestBody CompanyDataService.DeletionRequest body) {
    requireOwner(s, DELETION_OWNER_ONLY);
    return data.requestDeletion(s, body);
  }

  @DeleteMapping("/company/deletion")
  void cancelDeletion(ShopSession s) {
    requireOwner(s, DELETION_OWNER_ONLY);
    data.cancelDeletion(s);
  }

  private static void requireOwner(ShopSession s, String message) {
    if (!UserRules.isOwner(s.user().role())) {
      throw ApiException.forbidden(message);
    }
  }
}
