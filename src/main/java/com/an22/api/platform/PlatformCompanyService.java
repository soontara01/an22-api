package com.an22.api.platform;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.auth.MfaDao;
import com.an22.api.auth.PasswordResetTokens;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.billing.BillingService;
import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.BillingInvoice;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.NewCompanyPayload;
import com.an22.api.model.NewCompanyResult;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformCompany;
import com.an22.api.model.SetupLink;
import com.an22.api.platform.PlatformCompanyDao.CompanyRecord;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.CompanyDataRules;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.MfaRules;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.PlatformRules;
import com.an22.api.rules.SignupRules;
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
  static final String CODE_TAKEN = "รหัสบริษัทนี้ถูกใช้แล้ว";
  static final String NO_OWNER = "บริษัทนี้ไม่มีเจ้าของบริษัท";

  private final PlatformCompanyDao dao;
  private final NewCompanyDao newCompanies;
  private final AuditDao audit;
  private final ActorDao actors;
  private final PasswordResetTokens links;
  private final BillingService billing;
  private final MfaDao mfa;
  private final Clock clock;

  PlatformCompanyService(PlatformCompanyDao dao, NewCompanyDao newCompanies, AuditDao audit, ActorDao actors, PasswordResetTokens links,
      BillingService billing, MfaDao mfa, Clock clock) {
    this.dao = dao;
    this.newCompanies = newCompanies;
    this.audit = audit;
    this.actors = actors;
    this.links = links;
    this.billing = billing;
    this.mfa = mfa;
    this.clock = clock;
  }

  /**
   * {@code POST /platform/companies} (E59 — ทางเดียวที่สร้างบริษัท; แทน signup E37): ทดลองใช้ {@code trialDays} วัน (0 = ไม่มี — ใบแจ้งหนี้งวดแรก
   * ออกวันนี้ทันที), ข้อมูลบริษัท, สาขาแรก, owner ที่ยังไม่มีรหัสผ่าน, ช่องทางเงินสด — คืนหน้าบริษัท + ลิงก์ตั้งรหัสผ่านของ owner (แสดงครั้งเดียว).
   * รหัสเคยใช้ (รวมบริษัทที่ลบแล้ว) → 400.
   */
  @Transactional("platformTx")
  public NewCompanyResult create(PlatformAdmin admin, NewCompanyPayload in) {
    NewCompanyPayload p = SignupRules.normalize(in);
    badRequest(SignupRules.newCompanyError(p));
    String code = CompanyRules.normalizeCompanyCode(p.companyCode());
    if (newCompanies.codeTaken(code)) {
      throw ApiException.badRequest(CODE_TAKEN);
    }
    LocalDate today = LocalDate.now(clock);
    LocalDate trialEndsOn = p.trialDays() > 0 ? today.plusDays(p.trialDays()) : null;
    // สร้างพร้อมกันด้วยรหัสเดียวกัน: unique ของ code ตัดสิน
    long companyId = newCompanies.insertCompany(code, p.planId(), trialEndsOn, trialEndsOn == null ? today : trialEndsOn.plusDays(1))
        .orElseThrow(() -> ApiException.badRequest(CODE_TAKEN));
    actors.becomeOnPlatform(new TxActor(companyId, admin.id(), "platform"));
    NewCompanyPayload.Owner o = p.owner();
    long ownerId = newCompanies.insertOwner(UserRules.normalizeUsername(o.username()), o.name().strip(), o.email().strip());
    newCompanies.insertCompanyInfo(p.companyName(), p.taxId(), p.vatRegistered());
    BranchPayload b = p.branch();
    newCompanies.insertBranch(CompanyRules.normalizeBranch(CompanyRules.branchDefaults(),
        new BranchPayload(b.code(), b.name(), b.branchNo(), b.address(), b.phone(), CompanyRules.DEFAULT_RECEIPT_FOOTER, true)));
    newCompanies.insertCashMethod();
    CompanyRecord c = dao.find(companyId).orElseThrow();
    record(admin, c, "company_create", "company", companyId, code, "แพ็กเกจ " + PlanRules.planOf(p.planId()).name() + " "
        + (trialEndsOn == null ? "ไม่มีช่วงทดลองใช้" : "ทดลองใช้ถึง " + trialEndsOn));
    SetupLink link = links.issueSetup(companyId, ownerId);
    // ไม่มีช่วงทดลอง: ออกใบแจ้งหนี้งวดแรกตอนนี้เลย (ไม่รอ job / login — เหมือน mock)
    billing.catchUpIn(companyId);
    return new NewCompanyResult(detail(companyId), link);
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

  /**
   * {@code POST /platform/companies/:id/owner-setup-link} (E59 — แทน {@code owner-password}: platform ไม่พิมพ์ / ไม่รู้รหัสผ่านลูกค้า): ลิงก์ใหม่ให้
   * owner ที่ใช้งานอยู่ — ลิงก์ที่ยังไม่ใช้ของ owner คนนั้นใช้ไม่ได้ทันที; ใช้ได้ทั้งตั้งครั้งแรกและเจ้าของลืมรหัส (ยืนยันตัวตนก่อน).
   */
  @Transactional("platformTx")
  public SetupLink ownerSetupLink(PlatformAdmin admin, long id) {
    CompanyRecord c = writable(id);
    PlatformCompany.Owner owner = dao.activeOwner(id).orElseThrow(() -> ApiException.badRequest(NO_OWNER));
    SetupLink link = links.issueSetup(id, owner.id());
    record(admin, c, "owner_setup_link", "user", owner.id(), owner.username(), "ลิงก์ใช้ได้ถึง " + link.expiresAt());
    return link;
  }

  /**
   * {@code POST /platform/companies/:id/mfa-reset { username }} (E58): ล้าง Authenticator ของผู้ใช้ร้าน (เช่น owner ทำมือถือหายและไม่มี
   * ผู้ดูแลระดับบริษัทคนอื่น) — ผูกใหม่ตอน login ครั้งถัดไป.
   */
  @Transactional("platformTx")
  public void resetUserMfa(PlatformAdmin admin, long id, String usernameIn) {
    CompanyRecord c = writable(id);
    String username = UserRules.normalizeUsername(usernameIn == null ? "" : usernameIn);
    PlatformCompany.Owner user = dao.userByUsername(id, username).orElseThrow(() -> ApiException.badRequest(MfaRules.USER_NOT_FOUND));
    boolean on = mfa.find(Via.PLATFORM, id, user.id()).filter(MfaDao.Row::confirmed).isPresent();
    if (!on) {
      throw ApiException.badRequest(MfaRules.NOT_SET);
    }
    mfa.delete(Via.PLATFORM, id, user.id());
    record(admin, c, "mfa_reset", "user", user.id(), user.username(), "ผู้ให้บริการล้าง Microsoft Authenticator ให้");
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
    boolean deleted = c.deletedAt() != null;
    return PlatformCompany.Detail.of(row, newestFirst, deleted ? null : dao.owner(c.id()).orElse(null),
        !deleted && dao.ownerPasswordSet(c.id()), c.suspendNote());
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
