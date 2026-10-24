package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.SignupPayload;
import com.an22.api.model.User;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.SignupRules;
import com.an22.api.rules.UserRules;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * บัญชีที่ทำก่อนมี session (public chain): สมัครใช้งาน (E37), ลืมรหัสผ่าน / ตั้งรหัสด้วยลิงก์ของ owner (E41) — ตาม mock. signup / forgot นับครั้ง
 * ({@link LoginThrottle}) ก่อนแล้วจึงเปิด platformTx ใน {@link AccountTx} (ไม่ซ้อน — B5).
 */
@Service
public class AccountService {

  public static final String CODE_TAKEN = "รหัสบริษัทนี้ถูกใช้แล้ว";
  public static final String INVALID_LINK = "ลิงก์ตั้งรหัสผ่านไม่ถูกต้อง หมดอายุ หรือใช้ไปแล้ว — ขอลิงก์ใหม่";

  /** สมัครใช้งานต่อ IP ใน 15 นาที (นับทุกครั้ง — กันสร้างบริษัทรัว / ไล่เดารหัสบริษัทที่มีอยู่). */
  static final int SIGNUP_IP_MAX = 10;
  /** ลืมรหัสผ่านต่อ IP / ต่อรหัสบริษัท + อีเมล ใน 15 นาที (กันยิงอีเมลใส่ owner). */
  static final int FORGOT_IP_MAX = 10;
  static final int FORGOT_ACCOUNT_MAX = 3;

  private final AccountDao dao;
  private final AccountTx tx;
  private final LoginThrottle throttle;
  private final SessionDao sessionDao;
  private final AuditDao audit;
  private final ActorDao actors;
  private final PasswordEncoder passwords;
  private final PiiCipher cipher;
  private final Clock clock;

  AccountService(AccountDao dao, AccountTx tx, LoginThrottle throttle, SessionDao sessionDao, AuditDao audit, ActorDao actors,
      PasswordEncoder passwords, PiiCipher cipher, Clock clock) {
    this.dao = dao;
    this.tx = tx;
    this.throttle = throttle;
    this.sessionDao = sessionDao;
    this.audit = audit;
    this.actors = actors;
    this.passwords = passwords;
    this.cipher = cipher;
    this.clock = clock;
  }

  public record ForgotPasswordRequest(String companyCode, String email) {}

  public record ResetPasswordRequest(String token, String password) {}

  public record Message(String message) {}

  /**
   * {@code POST /auth/signup}: บริษัททดลองใช้ {@code TRIAL_DAYS} วันของแพ็กเกจที่เลือก + ข้อมูลบริษัท, สาขาแรก, owner, ช่องทางเงินสด — แล้ว login
   * เป็น owner. รหัสเคยใช้ (รวมบริษัทที่ลบแล้ว) → 400. เกิน {@link #SIGNUP_IP_MAX} ครั้ง / IP → 429.
   */
  public LoginResponse signup(SignupPayload in, String ip) {
    limit(Map.of(new ThrottleKey("ip", "signup:" + ip), SIGNUP_IP_MAX));
    return tx.signup(in);
  }

  /**
   * {@code POST /auth/forgot-password}: ตอบข้อความเดียวเสมอ — รหัสบริษัท + อีเมลตรงกับ owner ที่ใช้งานอยู่ → ลิงก์ใช้ครั้งเดียว อายุ
   * {@code RESET_LINK_MINUTES} นาที ทางอีเมล. เก็บแค่ HMAC ของ token; ลิงก์ขึ้นต้นด้วย {@code APP_BASE_URL} (ไม่ใช่ header Host).
   * เกินเพดานต่อ IP / ต่อรหัสบริษัท + อีเมลที่พิมพ์ (นับแม้ไม่มีจริง — 429 ไม่บอกว่ามีหรือไม่) → 429.
   */
  public Message forgotPassword(ForgotPasswordRequest req, String ip) {
    String code = CompanyRules.normalizeCompanyCode(req.companyCode());
    String wanted = req.email() == null ? "" : req.email().strip().toLowerCase(Locale.ROOT);
    limit(Map.of(new ThrottleKey("ip", "forgot:" + ip), FORGOT_IP_MAX,
        new ThrottleKey("account", "forgot:" + code + ":" + wanted), FORGOT_ACCOUNT_MAX));
    tx.forgotPassword(code, wanted);
    return new Message(SignupRules.FORGOT_PASSWORD_ANSWER);
  }

  /**
   * ล็อกอยู่ / ครบเพดาน → 429; ไม่งั้นนับครั้งนี้ (atomic — B6; transaction แยก นับแม้ request ตอบ 400) — เรียกก่อน {@link AccountTx} ไม่ซ้อน
   * transaction (B5).
   */
  private void limit(Map<ThrottleKey, Integer> maxByKey) {
    throttle.count(maxByKey);
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
