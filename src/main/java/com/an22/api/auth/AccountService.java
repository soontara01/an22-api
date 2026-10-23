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
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * บัญชีที่ทำก่อนมี session (public chain, platformTx): สมัครใช้งาน (E37), ลืมรหัสผ่าน / ตั้งรหัสด้วยลิงก์ของ owner (E41) — ตาม mock.
 */
@Service
public class AccountService {

  public static final String CODE_TAKEN = "รหัสบริษัทนี้ถูกใช้แล้ว";
  public static final String INVALID_LINK = "ลิงก์ตั้งรหัสผ่านไม่ถูกต้อง หมดอายุ หรือใช้ไปแล้ว — ขอลิงก์ใหม่";

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

  AccountService(AccountDao dao, SessionDao sessionDao, SessionService sessions, AuditDao audit, ActorDao actors,
      PasswordEncoder passwords, JwtService jwt, PiiCipher cipher, Mailer mailer, ApiProperties props, Clock clock) {
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

  public record ForgotPasswordRequest(String companyCode, String email) {}

  public record ResetPasswordRequest(String token, String password) {}

  public record Message(String message) {}

  /**
   * {@code POST /auth/signup}: บริษัททดลองใช้ {@code TRIAL_DAYS} วันของแพ็กเกจที่เลือก + ข้อมูลบริษัท, สาขาแรก, owner, ช่องทางเงินสด — แล้ว login
   * เป็น owner. รหัสเคยใช้ (รวมบริษัทที่ลบแล้ว) → 400.
   */
  @Transactional("platformTx")
  public LoginResponse signup(SignupPayload in) {
    SignupPayload p = SignupRules.normalize(in);
    String problem = SignupRules.signupError(p);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    String code = CompanyRules.normalizeCompanyCode(p.companyCode());
    if (dao.codeTaken(code)) {
      throw ApiException.badRequest(CODE_TAKEN);
    }
    LocalDate trialEndsOn = LocalDate.now(clock).plusDays(SubscriptionRules.TRIAL_DAYS);
    // สมัครพร้อมกันด้วยรหัสเดียวกัน: unique ของ code ตัดสิน
    long companyId = dao.insertCompany(code, p.planId(), trialEndsOn, trialEndsOn.plusDays(1))
        .orElseThrow(() -> ApiException.badRequest(CODE_TAKEN));
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

  /**
   * {@code POST /auth/forgot-password}: ตอบข้อความเดียวเสมอ — รหัสบริษัท + อีเมลตรงกับ owner ที่ใช้งานอยู่ → ลิงก์ใช้ครั้งเดียว อายุ
   * {@code RESET_LINK_MINUTES} นาที ทางอีเมล. เก็บแค่ HMAC ของ token; ลิงก์ขึ้นต้นด้วย {@code APP_BASE_URL} (ไม่ใช่ header Host).
   */
  @Transactional("platformTx")
  public Message forgotPassword(ForgotPasswordRequest req) {
    Message answer = new Message(SignupRules.FORGOT_PASSWORD_ANSWER);
    String code = CompanyRules.normalizeCompanyCode(req.companyCode());
    String wanted = req.email() == null ? "" : req.email().strip().toLowerCase(Locale.ROOT);
    Optional<CompanyRow> company = code.isEmpty() ? Optional.empty() : sessionDao.companyByCode(Via.PLATFORM, code);
    if (company.isEmpty() || wanted.isEmpty()) {
      return answer;
    }
    long companyId = company.get().id();
    Optional<AccountDao.Owner> owner = dao.activeOwnerByEmail(companyId, wanted);
    if (owner.isEmpty()) {
      return answer;
    }
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    dao.insertResetToken(companyId, owner.get().id(), cipher.hmac(token),
        Instant.now(clock).plus(Duration.ofMinutes(SignupRules.RESET_LINK_MINUTES)));
    mailer.send(owner.get().email(), "ตั้งรหัสผ่านใหม่ — บริษัท " + code, baseUrl + "/auth/reset-password?token=" + token);
    return answer;
  }

  /** {@code POST /auth/reset-password}: ลิงก์ใช้ได้ครั้งเดียวก่อนหมดอายุ ของ owner ที่ยังใช้งานอยู่ในบริษัทที่ยังไม่ถูกลบ. */
  @Transactional("platformTx")
  public void resetPassword(ResetPasswordRequest req) {
    Optional<AccountDao.ResetLink> found = req.token() == null || req.token().isEmpty()
        ? Optional.empty() : dao.lockResetToken(cipher.hmac(req.token()));
    Instant now = Instant.now(clock);
    AccountDao.ResetLink link = found.filter(l -> !l.used() && !l.expiresAt().isBefore(now))
        .orElseThrow(() -> ApiException.badRequest(INVALID_LINK));
    sessionDao.company(Via.PLATFORM, link.companyId()).orElseThrow(() -> ApiException.badRequest(INVALID_LINK));
    User user = sessionDao.userById(Via.PLATFORM, link.companyId(), link.userId()).map(SessionDao.UserRow::user)
        .filter(User::active).orElseThrow(() -> ApiException.badRequest(INVALID_LINK));
    String problem = UserRules.passwordError(req.password() == null ? "" : req.password());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    actors.becomeOnPlatform(TxActor.user(link.companyId(), user.id()));
    dao.setPassword(link.companyId(), user.id(), passwords.encode(req.password()));
    dao.useResetToken(link.id(), now);
    audit.insertOnPlatform(new AuditEntry(link.companyId(), "user", user.id(), user.name(), null, null, "password_reset", "user", user.id(),
        user.username(), "ตั้งรหัสผ่านใหม่ด้วยลิงก์ทางอีเมล"));
  }
}
