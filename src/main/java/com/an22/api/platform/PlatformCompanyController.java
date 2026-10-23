package com.an22.api.platform;

import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformCompany;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /platform/companies/*}, {@code /platform/invoices/:id/pay} (E36) — token ของ platform เท่านั้น (PlatformSessionFilter). */
@RestController
public class PlatformCompanyController {

  public record PlanChange(String planId) {}

  public record Suspend(String note) {}

  public record OwnerPassword(String password) {}

  private final PlatformCompanyService service;

  PlatformCompanyController(PlatformCompanyService service) {
    this.service = service;
  }

  @GetMapping("/platform/companies")
  List<PlatformCompany.Row> list(PlatformAdmin admin) {
    return service.list();
  }

  @GetMapping("/platform/companies/{id}")
  PlatformCompany.Detail detail(PlatformAdmin admin, @PathVariable long id) {
    return service.detail(id);
  }

  @PutMapping("/platform/companies/{id}/plan")
  PlatformCompany.Detail plan(PlatformAdmin admin, @PathVariable long id, @RequestBody PlanChange body) {
    return service.changePlan(admin, id, body.planId());
  }

  @PostMapping("/platform/companies/{id}/suspend")
  PlatformCompany.Detail suspend(PlatformAdmin admin, @PathVariable long id, @RequestBody(required = false) Suspend body) {
    return service.suspend(admin, id, body == null ? null : body.note());
  }

  @PostMapping("/platform/companies/{id}/reactivate")
  PlatformCompany.Detail reactivate(PlatformAdmin admin, @PathVariable long id) {
    return service.reactivate(admin, id);
  }

  /** ตอบ body ว่าง. */
  @PutMapping("/platform/companies/{id}/owner-password")
  void ownerPassword(PlatformAdmin admin, @PathVariable long id, @RequestBody OwnerPassword body) {
    service.ownerPassword(admin, id, body.password());
  }

  @PostMapping("/platform/companies/{id}/purge")
  PlatformCompany.Detail purge(PlatformAdmin admin, @PathVariable long id) {
    return service.purge(id);
  }

  @PostMapping("/platform/invoices/{id}/pay")
  PlatformCompany.Detail pay(PlatformAdmin admin, @PathVariable long id) {
    return service.payInvoice(admin, id);
  }
}
