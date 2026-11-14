package com.an22.api.platform;

import com.an22.api.auth.LoginThrottle;
import com.an22.api.auth.ThrottleKey;
import com.an22.api.model.MfaChallenge;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformLoginResponse;
import com.an22.api.platform.PlatformAdminDao.AdminRow;
import com.an22.api.rules.MfaRules;
import com.an22.api.rules.UserRules;
import com.an22.api.security.JwtService;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** login / session ของ platform admin (E36) — token แยก ({@code aud = platform}). */
@Service
public class PlatformAuthService {

  public static final String WRONG_LOGIN = "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง";

  private final PlatformAdminDao dao;
  private final LoginThrottle throttle;
  private final PlatformLoginTx tx;
  private final JwtService jwt;

  PlatformAuthService(PlatformAdminDao dao, LoginThrottle throttle, PlatformLoginTx tx, JwtService jwt) {
    this.dao = dao;
    this.throttle = throttle;
    this.tx = tx;
    this.jwt = jwt;
  }

  public record LoginRequest(String username, String password) {}

  /**
   * {@code POST /platform/auth/login}: ผิด → 401 ข้อความเดียว. ไม่เปิด transaction เอง — จองสิทธิ์ลอง (B6) / {@link PlatformLoginTx#verify} /
   * ผล เป็น transaction ต่อกัน ไม่ซ้อน (B5).
   */
  public record MfaVerifyRequest(String code) {}

  /**
   * {@code POST /platform/auth/login}: รหัสผ่านถูก → ขั้นรหัส Authenticator เสมอ (E56, {@link MfaChallenge}) — ยังไม่ออก token และไม่ล้างตัวนับของ
   * บัญชี (รหัส 6 หลักที่ผิดนับในตัวนับเดียวกัน).
   */
  public MfaChallenge login(LoginRequest req, String ip) {
    String username = UserRules.normalizeUsername(req.username());
    List<ThrottleKey> keys = List.of(ThrottleKey.platform(username), ThrottleKey.ip(ip));
    throttle.attempt(keys);
    boolean wrong = false;
    boolean ok = false;
    try {
      Optional<PlatformAdmin> admin = tx.verify(username, req.password());
      if (admin.isEmpty()) {
        wrong = true;
        throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_LOGIN);
      }
      return tx.challenge(admin.get());
    } finally {
      // ผิด = สิทธิ์ที่จองนับเป็นความผิด; ถูก / error = คืน (ไม่ล้างตัวนับ — ยังไม่ผ่านขั้นรหัส)
      if (!wrong) {
        throttle.passed(keys, ok);
      }
    }
  }

  /**
   * {@code POST /platform/auth/mfa/verify} (E56, Bearer = challenge ที่ an22-web เก็บไว้): ไม่มี / หมดอายุ → 400; รหัสผิด / ใช้แล้ว → 400 และนับผิด;
   * ถูก → token ของ platform. ไม่เปิด transaction เอง (B5).
   */
  public PlatformLoginResponse verifyMfa(String challenge, MfaVerifyRequest req, String ip) {
    Long adminId = jwt.decodePlatformMfa(challenge).map(t -> {
      try {
        return Long.parseLong(t.getSubject());
      } catch (NumberFormatException e) {
        return null;
      }
    }).orElse(null);
    PlatformAdmin admin = adminId == null ? null : tx.admin(adminId).orElse(null);
    if (admin == null) {
      throw ApiException.badRequest(MfaRules.CHALLENGE_EXPIRED);
    }
    List<ThrottleKey> keys = List.of(ThrottleKey.platform(admin.username()), ThrottleKey.ip(ip));
    throttle.attempt(keys);
    boolean wrong = false;
    boolean ok = false;
    try {
      if (!tx.useCode(admin.id(), req == null ? "" : req.code())) {
        wrong = true;
        throw ApiException.badRequest(MfaRules.WRONG_CODE);
      }
      PlatformLoginResponse response = new PlatformLoginResponse(jwt.issuePlatform(admin.id()), admin);
      ok = true;
      return response;
    } finally {
      if (!wrong) {
        throttle.passed(keys, ok);
      }
    }
  }

  /** admin ที่ token ระบุ (PlatformSessionFilter ทุก request) — ไม่มี = 401. */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<PlatformAdmin> current(long adminId) {
    return dao.byId(adminId).map(AdminRow::admin);
  }
}
