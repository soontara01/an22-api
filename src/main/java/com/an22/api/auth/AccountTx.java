package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.config.ApiProperties;
import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.mail.Mailer;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.SignupPayload;
import com.an22.api.model.User;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.SignupRules;
import com.an22.api.rules.SubscriptionRules;
import com.an22.api.rules.UserRules;
import com.an22.api.security.JwtService;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ส่วนของสมัครใช้งาน / ลืมรหัสผ่านที่อยู่ใน platformTx — แยกจาก {@link AccountService} เพื่อให้ตัวนับ ({@link LoginThrottle}, REQUIRES_NEW, pool
 * platform) ถูกเรียกนอก transaction นี้ (ไม่ซ้อน connection จาก pool เดียวกัน — B5).
 */
@Service
class AccountTx {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AccountDao dao;
  private final SessionDao sessionDao;
  private final SessionService sessions;
  private final AuditDao audit;
  private final ActorDao actors;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final PiiCipher cipher;
  private final Mailer mailer;
  private final String baseUrl;
  private final Clock clock;

  AccountTx(AccountDao dao, SessionDao sessionDao, SessionService sessions, AuditDao audit, ActorDao actors, PasswordEncoder passwords,
      JwtService jwt, PiiCipher cipher, Mailer mailer, ApiProperties props, Clock clock) {
    this.dao = dao;
    this.sessionDao = sessionDao;
    this.sessions = sessions;
    this.audit = audit;
    this.actors = actors;
    this.passwords = passwords;
    this.jwt = jwt;
    this.cipher = cipher;
    this.mailer = mailer;
    this.baseUrl = props.app().baseUrl().replaceAll("/+$", "");
    this.clock = clock;
  }

  /** {@link AccountService#signup} หลังนับครั้งแล้ว. */
  @Transactional("platformTx")
  public LoginResponse signup(SignupPayload in) {
    SignupPayload p = SignupRules.normalize(in);
    String problem = SignupRules.signupError(p);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    String code = CompanyRules.normalizeCompanyCode(p.companyCode());
    if (dao.codeTaken(code)) {
      throw ApiException.badRequest(AccountService.CODE_TAKEN);
    }
    LocalDate trialEndsOn = LocalDate.now(clock).plusDays(SubscriptionRules.TRIAL_DAYS);
    // สมัครพร้อมกันด้วยรหัสเดียวกัน: unique ของ code ตัดสิน
    long companyId = dao.insertCompany(code, p.planId(), trialEndsOn, trialEndsOn.plusDays(1))
        .orElseThrow(() -> ApiException.badRequest(AccountService.CODE_TAKEN));
    actors.becomeOnPlatform(new TxActor(companyId, null, "system"));
    SignupPayload.Owner o = p.owner();
    long ownerId = dao.insertOwner(UserRules.normalizeUsername(o.username()), o.name().strip(), o.email().strip(),
        passwords.encode(o.password()));
    actors.becomeOnPlatform(TxActor.user(companyId, ownerId));
    dao.insertCompanyInfo(p.companyName(), p.taxId(), p.vatRegistered());
    BranchPayload b = p.branch();
    dao.insertBranch(CompanyRules.normalizeBranch(CompanyRules.branchDefaults(),
        new BranchPayload(b.code(), b.name(), b.branchNo(), b.address(), b.phone(), CompanyRules.DEFAULT_RECEIPT_FOOTER, true)));
    dao.insertCashMethod();

    CompanyRow c = sessionDao.company(Via.PLATFORM, companyId).orElseThrow();
    User owner = sessionDao.userById(Via.PLATFORM, companyId, ownerId).orElseThrow().user();
    audit.insertOnPlatform(new AuditEntry(companyId, "user", ownerId, owner.name(), null, null, "signup", "company", companyId, code,
        "สมัครใช้งาน แพ็กเกจ " + PlanRules.planOf(p.planId()).name() + " ทดลองใช้ถึง " + trialEndsOn));
    ShopSession session = SessionService.of(c, owner, sessionDao.branches(Via.PLATFORM, companyId), null);
    String token = jwt.issueShop(companyId, ownerId, owner.role(), owner.allBranches(), owner.branchIds());
    return new LoginResponse(token, SessionService.toSession(session, sessions.billing(session, Via.PLATFORM)));
  }

  /** {@link AccountService#forgotPassword} หลังนับครั้งแล้ว: {@code code} / {@code wanted} normalize แล้ว — ไม่ตรง = ไม่ทำอะไร. */
  @Transactional("platformTx")
  public void forgotPassword(String code, String wanted) {
    Optional<CompanyRow> company = code.isEmpty() ? Optional.empty() : sessionDao.companyByCode(Via.PLATFORM, code);
    if (company.isEmpty() || wanted.isEmpty()) {
      return;
    }
    long companyId = company.get().id();
    Optional<AccountDao.Owner> owner = dao.activeOwnerByEmail(companyId, wanted);
    if (owner.isEmpty()) {
      return;
    }
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    dao.insertResetToken(companyId, owner.get().id(), cipher.hmac(token),
        Instant.now(clock).plus(Duration.ofMinutes(SignupRules.RESET_LINK_MINUTES)));
    mailer.send(owner.get().email(), "ตั้งรหัสผ่านใหม่ — บริษัท " + code, baseUrl + "/auth/reset-password?token=" + token);
  }
}
