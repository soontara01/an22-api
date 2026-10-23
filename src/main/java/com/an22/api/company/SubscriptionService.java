package com.an22.api.company;

import com.an22.api.audit.Audit;
import com.an22.api.auth.SessionDao;
import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.auth.ShopSession;
import com.an22.api.model.BillingInvoice;
import com.an22.api.model.OnboardingCounts;
import com.an22.api.model.SubscriptionInfo;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.SubscriptionRules;
import com.an22.api.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** แพ็กเกจ / การเก็บเงินของบริษัท (E34 / E35) + ขั้นตอนเริ่มต้นใช้งาน (E37) — สิทธิ์ตรวจที่ controller. */
@Service
public class SubscriptionService {

  private final SessionDao sessions;
  private final CompanyAccountDao accounts;
  private final PlanLimits limits;
  private final Audit audit;
  private final Clock clock;

  SubscriptionService(SessionDao sessions, CompanyAccountDao accounts, PlanLimits limits, Audit audit, Clock clock) {
    this.sessions = sessions;
    this.accounts = accounts;
    this.limits = limits;
    this.audit = audit;
    this.clock = clock;
  }

  /** {@code GET /company/subscription}. */
  @Transactional(readOnly = true)
  public SubscriptionInfo subscription(ShopSession s) {
    CompanyRow c = sessions.company(Via.APP, s.tenantId()).orElseThrow();
    return info(c, limits.usage(), sessions.invoices(Via.APP, s.tenantId()));
  }

  /**
   * {@code PUT /company/plan} (owner): ลดแพ็กเกจได้แม้ใช้เกินเพดาน — แค่เพิ่มต่อไม่ได้ (E34). ใบแจ้งหนี้รอบถัดไปคิดราคาแพ็กเกจ ณ วันออก.
   */
  @Transactional("platformTx")
  public SubscriptionInfo changePlan(ShopSession s, String planId) {
    PlanRules.Plan plan = PlanRules.PLANS.stream().filter(p -> p.id().equals(planId)).findFirst()
        .orElseThrow(() -> ApiException.badRequest("ไม่มีแพ็กเกจนี้"));
    accounts.setPlan(s.tenantId(), plan.id());
    audit.recordOnPlatform(s, "plan_change", null, "company", s.tenantId(), s.companyCode(),
        "เปลี่ยนเป็นแพ็กเกจ " + plan.name() + " (฿" + plan.monthlyPrice() + " / เดือน)");
    CompanyRow c = sessions.company(Via.PLATFORM, s.tenantId()).orElseThrow();
    return info(c, accounts.usageOnPlatform(s.tenantId()), sessions.invoices(Via.PLATFORM, s.tenantId()));
  }

  /** {@code GET /company/onboarding}. */
  @Transactional(readOnly = true)
  public OnboardingCounts onboarding() {
    return accounts.onboarding();
  }

  private SubscriptionInfo info(CompanyRow c, PlanRules.Usage usage, List<BillingInvoice> invoices) {
    String status = SubscriptionRules.subscriptionStatus(c.subscription(), invoices, LocalDate.now(clock)).json();
    List<BillingInvoice> newestFirst = invoices.stream().sorted(Comparator.comparingLong(BillingInvoice::id).reversed()).toList();
    return new SubscriptionInfo(c.planId(), usage, status, c.subscription(), newestFirst);
  }
}
