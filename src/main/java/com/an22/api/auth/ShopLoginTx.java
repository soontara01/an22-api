package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.UserRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.LoginResult;
import com.an22.api.model.User;
import com.an22.api.rules.MfaRules;
import com.an22.api.rules.UserRules;
import com.an22.api.security.JwtService;
import com.an22.api.web.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ส่วนของ login ร้านที่อยู่ใน platformTx (ตรวจรหัส, audit login, ออก token) — แยกจาก {@link AuthService#login} เพื่อให้
 * {@link LoginThrottle} และ {@code BillingService.catchUp} (REQUIRES_NEW, pool platform) ถูกเรียก<b>นอก</b> transaction นี้: ถ้าซ้อนกัน 1 request ต้องใช้ 2 connection จาก pool
 * เดียวกัน → login พร้อมกันเท่าขนาด pool = pool ติดตาย (E2E ข้อ 34.7 / B5).
 */
@Service
class ShopLoginTx {

  private final SessionDao dao;
  private final SessionService sessions;
  private final AuditDao audit;
  private final ActorDao actors;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final MfaService mfa;
  /** hash ที่ไม่ตรงกับอะไรเลย — ตรวจเมื่อไม่มีผู้ใช้ เวลาตอบจะได้ไม่บอกว่าชื่อนี้มีอยู่. */
  private final String dummyHash;

  ShopLoginTx(
      SessionDao dao,
      SessionService sessions,
      AuditDao audit,
      ActorDao actors,
      PasswordEncoder passwords,
      JwtService jwt,
      MfaService mfa) {
    this.dao = dao;
    this.sessions = sessions;
    this.audit = audit;
    this.actors = actors;
    this.passwords = passwords;
    this.jwt = jwt;
    this.mfa = mfa;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  /** รหัสบริษัท / ผู้ใช้ / รหัสผ่านผิด — transaction rollback, ผู้เรียกนับผิด ({@code failedLogin} = audit ที่ต้องบันทึก ถ้ามี). */
  static final class Rejected extends RuntimeException {
    final transient AuditEntry failedLogin;

    Rejected(AuditEntry failedLogin) {
      super(null, null, false, false);
      this.failedLogin = failedLogin;
    }
  }

  /** บริษัท + ชื่อผู้ใช้ของ challenge (E55) — ตัวนับผิดของบัญชีเดียวกับ login; ไม่มี / ผู้ใช้ปิด = empty. */
  record Who(String companyCode, String username) {}

  @Transactional(value = "platformTx", readOnly = true)
  public Optional<Who> who(long tenantId, long userId) {
    Optional<CompanyRow> company = dao.company(Via.PLATFORM, tenantId);
    Optional<User> user = dao.userById(Via.PLATFORM, tenantId, userId).map(UserRow::user).filter(User::active);
    return company.isEmpty() || user.isEmpty()
        ? Optional.empty()
        : Optional.of(new Who(company.get().code(), user.get().username()));
  }

  /** id ของบริษัทที่รหัสนี้ (ให้ {@link AuthService#login} เรียก billing catch-up ใน transaction ของมันเองก่อน {@link #attempt}). */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<Long> companyId(String code) {
    return code.isEmpty() ? Optional.empty() : dao.companyByCode(Via.PLATFORM, code).map(CompanyRow::id);
  }

  /**
   * {@code code} / {@code username} normalize แล้ว — billing catch-up ทำแล้ว (ผู้เรียก). ผิด → {@link Rejected}; รหัสถูกแต่บริษัทถูกระงับ → 401 ข้อความตามเหตุ (ไม่นับผิด ไม่ล้างตัวนับ).
   * ต้องใช้รหัส Authenticator ({@code loginNeedsMfa()}, E55) → {@link com.an22.api.model.MfaChallenge} ยังไม่ออก token / ไม่ audit {@code login}.
   */
  @Transactional("platformTx")
  public LoginResult attempt(String code, String username, String password, boolean askedMfa) {
    Optional<CompanyRow> company = code.isEmpty() ? Optional.empty() : dao.companyByCode(Via.PLATFORM, code);
    if (company.isEmpty()) {
      passwords.matches(String.valueOf(password), dummyHash);
      throw new Rejected(null);
    }
    CompanyRow c = company.get();
    Optional<User> user = check(Via.PLATFORM, c.id(), username, password);
    if (user.isEmpty()) {
      // ชื่อที่พิมพ์ ไม่มี userId (อาจไม่มีผู้ใช้นี้ — ข้อความเดียวกัน E3)
      throw new Rejected(AuditEntry.ofUser(c.id(), null, username, null, "login_failed", AuthService.WRONG_LOGIN));
    }
    User u = user.get();
    boolean ownerDeleting = "deletion".equals(c.suspendedReason()) && UserRules.isOwner(u.role());
    if (!c.active() && !ownerDeleting) {
      throw new ApiException(HttpStatus.UNAUTHORIZED,
          AuthService.SUSPENDED_MESSAGE.getOrDefault(c.suspendedReason(), AuthService.SUSPENDED_MESSAGE.get("platform")));
    }
    actors.becomeOnPlatform(TxActor.user(c.id(), u.id()));
    if (MfaRules.loginNeedsMfa(u.role(), u.mfaEnabled(), c.mfaRequired(), askedMfa)) {
      return mfa.challenge(Via.PLATFORM, c.id(), c.code(), u);
    }
    return session(c, u, "");
  }

  /**
   * ขั้นรหัสของ login (E55): บริษัท / ผู้ใช้ของ challenge (ไม่มี / ปิด → 400 หมดเวลา), บริษัทถูกระงับ → 401 เหมือน login, รหัสผิด / ใช้แล้ว →
   * {@link Rejected} (audit {@code login_failed}); ถูก → เปิดใช้ถ้าเพิ่งผูก (audit {@code mfa_enable}) แล้ว session + token.
   */
  @Transactional("platformTx")
  public LoginResponse verifyMfa(long tenantId, long userId, String code) {
    CompanyRow c = dao.company(Via.PLATFORM, tenantId).orElseThrow(() -> ApiException.badRequest(MfaRules.CHALLENGE_EXPIRED));
    User u = dao.userById(Via.PLATFORM, tenantId, userId).map(UserRow::user).filter(User::active)
        .orElseThrow(() -> ApiException.badRequest(MfaRules.CHALLENGE_EXPIRED));
    boolean ownerDeleting = "deletion".equals(c.suspendedReason()) && UserRules.isOwner(u.role());
    if (!c.active() && !ownerDeleting) {
      throw new ApiException(HttpStatus.UNAUTHORIZED,
          AuthService.SUSPENDED_MESSAGE.getOrDefault(c.suspendedReason(), AuthService.SUSPENDED_MESSAGE.get("platform")));
    }
    actors.becomeOnPlatform(TxActor.user(c.id(), u.id()));
    if (!mfa.use(Via.PLATFORM, c.id(), u.id(), code)) {
      throw new Rejected(AuditEntry.ofUser(c.id(), u.id(), u.name(), null, "login_failed", "รหัส Microsoft Authenticator ไม่ถูกต้อง"));
    }
    if (!u.mfaEnabled()) {
      audit.insertOnPlatform(new AuditEntry(c.id(), "user", u.id(), u.name(), null, null, "mfa_enable", "user", u.id(), u.username(),
          "ผูกตอนเข้าสู่ระบบ"));
    }
    User now = dao.userById(Via.PLATFORM, tenantId, userId).map(UserRow::user).orElse(u);
    return session(c, now, "ด้วย Microsoft Authenticator");
  }

  /** audit {@code login} แล้วออก session + token. */
  private LoginResponse session(CompanyRow c, User u, String detail) {
    audit.insertOnPlatform(AuditEntry.ofUser(c.id(), u.id(), u.name(), null, "login", detail));
    ShopSession session = SessionService.of(c, u, dao.branches(Via.PLATFORM, c.id()), null);
    String token = jwt.issueShop(c.id(), u.id(), u.role(), u.allBranches(), u.branchIds());
    return new LoginResponse(token, SessionService.toSession(session, sessions.billing(session, Via.PLATFORM)));
  }

  /** ผู้ใช้ที่ใช้งานอยู่และรหัสตรง ({@code checkCredentials()} ของ mock) — ผู้ใช้ปิดใช้งาน = รหัสผิด (E3). ทำงานใน transaction ของผู้เรียก. */
  Optional<User> check(Via via, long tenantId, String username, String password) {
    Optional<UserRow> row = username.isEmpty() ? Optional.empty() : dao.userByUsername(via, tenantId, username);
    String raw = password == null ? "" : password;
    if (row.isEmpty()) {
      passwords.matches(raw, dummyHash);
      return Optional.empty();
    }
    boolean match = passwords.matches(raw, row.get().passwordHash());
    return match && row.get().user().active() ? Optional.of(row.get().user()) : Optional.empty();
  }
}
