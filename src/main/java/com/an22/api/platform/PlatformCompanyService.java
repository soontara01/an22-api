package com.an22.api.platform;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.model.BillingInvoice;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformCompany;
import com.an22.api.platform.PlatformCompanyDao.CompanyRecord;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.CompanyDataRules;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.PlatformRules;
import com.an22.api.rules.SubscriptionRules;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * งานของผู้ดูแลแพลตฟอร์มกับบริษัท (E36, platformTx) ตาม mock: ทุกการกระทำลง audit log ของบริษัทนั้น {@code actorKind: 'platform'}
 * (user_id null, ชื่อ = admin) ยกเว้น purge (ข้อมูลถูกลบทั้งหมด). billing ไม่คิดที่นี่ — job / login / me ทำ (แผนหัวข้อ "Stateless / scale").
 */
@Service
public class PlatformCompanyService {

  static final String NOT_FOUND = "ไม่พบบริษัท";
  static final String DELETED = "บริษัทนี้ถูกลบข้อมูลแล้ว";

  private final PlatformCompanyDao dao;
  private final AuditDao audit;
  private final PasswordEncoder passwords;
  private final Clock clock;

  PlatformCompanyService(PlatformCompanyDao dao, AuditDao audit, PasswordEncoder passwords, Clock clock) {
    this.dao = dao;
    this.audit = audit;
    this.passwords = passwords;
    this.clock = clock;
  }

  /** {@code GET /platform/companies}: ทุกบริษัท (รวมที่ลบแล้ว) เรียงตาม id. */
  @Transactional(value = "platformTx", readOnly = true)
  public List<PlatformCompany.Row> list() {
    Map<Long, List<BillingInvoice>> invoices = dao.invoices().stream().collect(Collectors.groupingBy(BillingInvoice::companyId));
    LocalDate today = LocalDate.now(clock);
    return dao.list().stream().map(c -> row(c, invoices.getOrDefault(c.id(), List.of()), today)).toList();
  }

  /** {@code GET /platform/companies/:id}. */
  @Transactional(value = "platformTx", readOnly = true)
  public PlatformCompany.Detail detail(long id) {
    return detailOf(dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND)));
  }

  /** {@code PUT /platform/companies/:id/plan}: ลดได้แม้ใช้เกิน (E34). */
  @Transactional("platformTx")
  public PlatformCompany.Detail changePlan(PlatformAdmin admin, long id, String planId) {
    CompanyRecord c = writable(id);
    PlanRules.Plan plan = PlanRules.PLANS.stream().filter(p -> p.id().equals(planId)).findFirst()
        .orElseThrow(() -> ApiException.badRequest("ไม่มีแพ็กเกจนี้"));
    dao.setPlan(id, plan.id());
    record(admin, c, "plan_change", "company", c.id(), c.code(), "ผู้ให้บริการเปลี่ยนเป็นแพ็กเกจ " + plan.name());
    return detail(id);
  }

  /** {@code POST /platform/companies/:id/suspend}. */
  @Transactional("platformTx")
  public PlatformCompany.Detail suspend(PlatformAdmin admin, long id, String noteIn) {
    CompanyRecord c = writable(id);
    String note = noteIn == null ? "" : noteIn.strip();
    badRequest(PlatformRules.suspendCompanyError(c.active(), false, note));
    dao.setStatus(id, false, "platform", note);
    record(admin, c, "company_suspend", "company", c.id(), c.code(), note);
    return detail(id);
  }

  /** {@code POST /platform/companies/:id/reactivate}: เฉพาะที่ platform ระงับ. */
  @Transactional("platformTx")
  public PlatformCompany.Detail reactivate(PlatformAdmin admin, long id) {
    CompanyRecord c = writable(id);
    badRequest(PlatformRules.reactivateCompanyError(c.active(), c.suspendedReason(), false));
    dao.setStatus(id, true, null, null);
    record(admin, c, "company_reactivate", "company", c.id(), c.code(), "");
    return detail(id);
  }

  /** {@code PUT /platform/companies/:id/owner-password}: ตั้งรหัสผ่านใหม่ให้ owner (owner ลืมรหัส + อีเมลใช้ไม่ได้). */
  @Transactional("platformTx")
  public void ownerPassword(PlatformAdmin admin, long id, String passwordIn) {
    CompanyRecord c = writable(id);
    String password = passwordIn == null ? "" : passwordIn;
    badRequest(UserRules.passwordError(password));
    PlatformCompany.Owner owner = dao.owner(id).orElseThrow(() -> ApiException.badRequest("บริษัทนี้ไม่มีเจ้าของบริษัท"));
    dao.setOwnerPassword(id, owner.id(), passwords.encode(password));
    record(admin, c, "user_password", "user", owner.id(), owner.username(), "ผู้ให้บริการตั้งรหัสผ่านใหม่ให้เจ้าของบริษัท");
  }

  /**
   * {@code POST /platform/companies/:id/purge}: มีคำขอลบและถึง {@code purgeAfter} แล้ว → ลบข้อมูลทุกตารางของบริษัทถาวร (E39); ทะเบียนบริษัท
   * คง {@code deleted_at} + ชื่อ (รหัสห้ามใช้ซ้ำ), login / token เดิม = ไม่มีบริษัท.
   */
  @Transactional("platformTx")
  public PlatformCompany.Detail purge(long id) {
    CompanyRecord c = writable(id);
    badRequest(CompanyDataRules.purgeError(c.deletion() == null ? null : c.deletion().purgeAfter(), LocalDate.now(clock)));
    dao.purge(id, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    return detail(id);
  }

  /** {@code POST /platform/invoices/:id/pay}: รับชำระ — ระงับเพราะค้างและไม่เหลือใบเกินผ่อนผัน → เปิดใช้งานเอง (E35). */
  @Transactional("platformTx")
  public PlatformCompany.Detail payInvoice(PlatformAdmin admin, long invoiceId) {
    BillingInvoice invoice = dao.lockInvoice(invoiceId).orElseThrow(() -> ApiException.notFound("ไม่พบใบแจ้งหนี้"));
    dao.lock(invoice.companyId());
    badRequest(PlatformRules.payInvoiceError(invoice.status()));
    dao.payInvoice(invoiceId, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    CompanyRecord c = dao.find(invoice.companyId()).orElseThrow();
    SubscriptionRules.Status status = SubscriptionRules.subscriptionStatus(c.subscription(), dao.invoicesOf(c.id()), LocalDate.now(clock));
    if ("overdue".equals(c.suspendedReason()) && status != SubscriptionRules.Status.SUSPENDED) {
      dao.setStatus(c.id(), true, null, null);
    }
    record(admin, c, "invoice_paid", "company", c.id(), invoice.invoiceNo(), AuditRules.auditBaht(invoice.amount()));
    return detail(c.id());
  }

  /** ล็อกแถวบริษัท → ไม่มี 404, ลบแล้ว 400 (ลำดับของ mock). */
  private CompanyRecord writable(long id) {
    if (!dao.lock(id)) {
      throw ApiException.notFound(NOT_FOUND);
    }
    CompanyRecord c = dao.find(id).orElseThrow();
    if (c.deletedAt() != null) {
      throw ApiException.badRequest(DELETED);
    }
    return c;
  }

  private PlatformCompany.Detail detailOf(CompanyRecord c) {
    List<BillingInvoice> invoices = dao.invoicesOf(c.id());
    PlatformCompany.Row row = row(c, invoices, LocalDate.now(clock));
    List<BillingInvoice> newestFirst = invoices.stream().sorted(Comparator.comparingLong(BillingInvoice::id).reversed()).toList();
    return PlatformCompany.Detail.of(row, newestFirst, c.deletedAt() == null ? dao.owner(c.id()).orElse(null) : null, c.suspendNote());
  }

  private static PlatformCompany.Row row(CompanyRecord c, List<BillingInvoice> invoices, LocalDate today) {
    BigDecimal open = invoices.stream().filter(i -> "open".equals(i.status())).map(BillingInvoice::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean deleted = c.deletedAt() != null;
    return new PlatformCompany.Row(c.id(), c.code(), c.name(), c.taxId(), c.planId(), c.active(), c.suspendedReason(),
        SubscriptionRules.subscriptionStatus(c.subscription(), invoices, today).json(),
        deleted ? new PlanRules.Usage(0, 0, 0) : c.usage(), c.subscription(), open, c.deletedAt(), c.deletion());
  }

  private void record(PlatformAdmin admin, CompanyRecord c, String action, String refKind, Long refId, String refLabel, String detail) {
    audit.insertOnPlatform(new AuditEntry(c.id(), "platform", null, admin.name(), null, null, action, refKind, refId, refLabel, detail));
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
