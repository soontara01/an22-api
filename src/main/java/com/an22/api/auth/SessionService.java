package com.an22.api.auth;

import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.UserRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.billing.BillingService;
import com.an22.api.model.BillingNotice;
import com.an22.api.model.Branch;
import com.an22.api.model.Session;
import com.an22.api.model.User;
import com.an22.api.rules.SubscriptionRules;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import com.an22.api.web.ErrorResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** session ของผู้ใช้ร้าน: โหลดทุก request (ShopSessionFilter) + {@code GET /me}. */
@Service
public class SessionService {

  private final SessionDao dao;
  private final Clock clock;

  private final BillingService billingService;

  SessionService(SessionDao dao, Clock clock, BillingService billingService) {
    this.billingService = billingService;
    this.dao = dao;
    this.clock = clock;
  }

  /**
   * ผู้ใช้ที่ token ระบุในบริษัทของ token ({@code requestUser()} ของ mock) — ไม่มี = 401: บริษัทไม่มี / ถูกลบ / ถูกระงับ
   * (ยกเว้นรอลบ: เฉพาะ owner), ผู้ใช้ไม่มี / ปิดใช้งาน. TxActor ของ request ตั้งบริษัทไว้แล้ว (RLS).
   */
  @Transactional(readOnly = true)
  public Optional<ShopSession> load(long tenantId, long userId, Long terminalHeader) {
    Optional<CompanyRow> company = dao.company(Via.APP, tenantId);
    if (company.isEmpty()) {
      return Optional.empty();
    }
    CompanyRow c = company.get();
    boolean deleting = "deletion".equals(c.suspendedReason());
    if (!c.active() && !deleting) {
      return Optional.empty();
    }
    Optional<User> user = dao.userById(Via.APP, tenantId, userId).map(UserRow::user);
    if (user.isEmpty() || !user.get().active() || (deleting && !UserRules.isOwner(user.get().role()))) {
      return Optional.empty();
    }
    Integer terminal =
        terminalHeader != null && terminalHeader <= Integer.MAX_VALUE
                && dao.terminalExists(Via.APP, tenantId, terminalHeader.intValue())
            ? terminalHeader.intValue()
            : null;
    return Optional.of(of(c, user.get(), dao.branches(Via.APP, tenantId), terminal));
  }

  /**
   * {@code GET /me}: ออกใบแจ้งหนี้ / ระงับค้างชำระที่ถึงรอบก่อน ({@link BillingService#catchUp}, transaction แยก) แล้วตอบ session ปัจจุบัน (ไม่ออก
   * token ใหม่) + แถบแจ้งเตือนการเก็บเงินของ owner / admin — ถูกระงับระหว่างนี้ = 401.
   */
  @Transactional(readOnly = true)
  public Session me(ShopSession session) {
    ShopSession current = session;
    if (billingService.catchUp(session.tenantId())) {
      current = load(session.tenantId(), session.user().id(), session.terminalId() == null ? null : session.terminalId().longValue())
          .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorResponse.SESSION_EXPIRED));
    }
    return toSession(current, billing(current, Via.APP));
  }

  /** ประกอบ session จากแถวที่อ่านแล้ว ({@code companySession()} ของ mock). */
  static ShopSession of(CompanyRow c, User user, List<Branch> allBranches, Integer terminalId) {
    return new ShopSession(
        c.id(), c.code(), c.active(), c.suspendedReason(), c.planId(), c.purgeAfter(), c.subscription(), c.name(), c.taxId(),
        c.staffSeeCost(), user, allBranches.stream().filter(b -> UserRules.canWorkIn(user, b)).toList(), terminalId);
  }

  /** แถบแจ้งเตือน (E35) เฉพาะ owner / admin — คนอื่น null. */
  BillingNotice billing(ShopSession session, Via via) {
    if (!UserRules.hasAdminRights(session.user().role())) {
      return null;
    }
    LocalDate today = LocalDate.now(clock);
    return SubscriptionRules.billingNotice(session.subscription(), dao.invoices(via, session.tenantId()), today);
  }

  static Session toSession(ShopSession s, BillingNotice billing) {
    return new Session(s.user(), s.company(), s.branches(), billing, s.canSeeCost());
  }
}
