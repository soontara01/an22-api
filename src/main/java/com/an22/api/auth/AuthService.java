package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.UserRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.User;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.UserRules;
import com.an22.api.security.JwtService;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * login ร้าน (D4: รหัสบริษัท + ชื่อผู้ใช้ + รหัสผ่าน) และตรวจรหัสโดยไม่สร้าง session ({@code auth/verify}) — ลำดับ / ข้อความตาม mock.
 * ผิดทุกกรณีข้อความเดียว (E3); บริษัทถูกระงับบอกเฉพาะเมื่อรหัสผ่านถูก.
 */
@Service
public class AuthService {

  public static final String WRONG_COMPANY_LOGIN = "รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง";
  public static final String WRONG_LOGIN = "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง";
  /** บอกหลังรหัสผ่านถูกเท่านั้น ตามเหตุที่ระงับ. */
  static final Map<String, String> SUSPENDED_MESSAGE =
      Map.of(
          "platform", "บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ",
          "overdue", "บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน",
          "deletion", "บริษัทนี้อยู่ระหว่างรอลบข้อมูล — ติดต่อเจ้าของบริษัท");

  private final SessionDao dao;
  private final SessionService sessions;
  private final LoginThrottle throttle;
  private final AuditDao audit;
  private final ActorDao actors;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  /** hash ที่ไม่ตรงกับอะไรเลย — ตรวจเมื่อไม่มีผู้ใช้ เวลาตอบจะได้ไม่บอกว่าชื่อนี้มีอยู่. */
  private final String dummyHash;

  AuthService(
      SessionDao dao,
      SessionService sessions,
      LoginThrottle throttle,
      AuditDao audit,
      ActorDao actors,
      PasswordEncoder passwords,
      JwtService jwt) {
    this.dao = dao;
    this.sessions = sessions;
    this.throttle = throttle;
    this.audit = audit;
    this.actors = actors;
    this.passwords = passwords;
    this.jwt = jwt;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record LoginRequest(String companyCode, String username, String password) {}

  public record VerifyRequest(String username, String password) {}

  /** {@code POST /auth/login} (platformTx — ยังไม่รู้บริษัท). */
  @Transactional("platformTx")
  public LoginResponse login(LoginRequest req, String ip) {
    String code = CompanyRules.normalizeCompanyCode(req.companyCode());
    String username = UserRules.normalizeUsername(req.username());
    ThrottleKey account = ThrottleKey.shop(code, username);
    List<ThrottleKey> keys = List.of(account, ThrottleKey.ip(ip));
    throttle.check(keys);

    Optional<CompanyRow> company = code.isEmpty() ? Optional.empty() : dao.companyByCode(Via.PLATFORM, code);
    if (company.isEmpty()) {
      passwords.matches(String.valueOf(req.password()), dummyHash);
      throttle.fail(keys, null);
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_COMPANY_LOGIN);
    }
    CompanyRow c = company.get();
    Optional<User> user = check(Via.PLATFORM, c.id(), username, req.password());
    if (user.isEmpty()) {
      // ชื่อที่พิมพ์ ไม่มี userId (อาจไม่มีผู้ใช้นี้ — ข้อความเดียวกัน E3)
      throttle.fail(keys, AuditEntry.ofUser(c.id(), null, username, null, "login_failed", WRONG_LOGIN));
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_COMPANY_LOGIN);
    }
    User u = user.get();
    boolean ownerDeleting = "deletion".equals(c.suspendedReason()) && UserRules.isOwner(u.role());
    if (!c.active() && !ownerDeleting) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, SUSPENDED_MESSAGE.getOrDefault(c.suspendedReason(), SUSPENDED_MESSAGE.get("platform")));
    }
    throttle.succeed(account);
    actors.becomeOnPlatform(TxActor.user(c.id(), u.id()));
    audit.insertOnPlatform(AuditEntry.ofUser(c.id(), u.id(), u.name(), null, "login", ""));
    ShopSession session = SessionService.of(c, u, dao.branches(Via.PLATFORM, c.id()), null);
    String token = jwt.issueShop(c.id(), u.id(), u.role(), u.allBranches(), u.branchIds());
    return new LoginResponse(token, SessionService.toSession(session, sessions.billing(session, Via.PLATFORM)));
  }

  /**
   * {@code POST /auth/verify}: รหัสของผู้ใช้ในบริษัทของ token (ปลดล็อก POS, ผู้อนุมัติ) — ผิดทุกกรณี 400 ข้อความเดียว (ห้าม 401: จะ
   * logout คนหน้าเครื่อง).
   */
  @Transactional(readOnly = true)
  public User verify(ShopSession session, VerifyRequest req, String ip) {
    String username = UserRules.normalizeUsername(req.username());
    List<ThrottleKey> keys = List.of(ThrottleKey.shop(session.companyCode(), username), ThrottleKey.ip(ip));
    throttle.check(keys);
    Optional<User> user = check(Via.APP, session.tenantId(), username, req.password());
    if (user.isEmpty()) {
      throttle.fail(keys, null);
      throw ApiException.badRequest(WRONG_LOGIN);
    }
    return user.get();
  }

  /** ผู้ใช้ที่ใช้งานอยู่และรหัสตรง ({@code checkCredentials()} ของ mock) — ผู้ใช้ปิดใช้งาน = รหัสผิด (E3). */
  private Optional<User> check(Via via, long tenantId, String username, String password) {
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
