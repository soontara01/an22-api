package com.an22.api.auth;

import com.an22.api.audit.Audit;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.model.MfaChange;
import com.an22.api.model.User;
import com.an22.api.rules.MfaRules;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ผู้ใช้เปลี่ยนเครื่อง Microsoft Authenticator ของตัวเองจากหน้าตั้งค่า (E58, {@code handleOwnMfa()} ของ mock) — appTx ของ session:
 * รหัสผ่าน + รหัสของเครื่องเดิม (นับผิดใน {@link LoginThrottle} บัญชีเดียวกับ login) → QR ของเครื่องใหม่ → รหัสแรกของเครื่องใหม่แทน secret.
 */
@Service
public class OwnMfaService {

  private final MfaService mfa;
  private final SessionDao sessions;
  private final AuthService auth;
  private final LoginThrottle throttle;
  private final Audit audit;

  OwnMfaService(MfaService mfa, SessionDao sessions, AuthService auth, LoginThrottle throttle, Audit audit) {
    this.mfa = mfa;
    this.sessions = sessions;
    this.auth = auth;
    this.throttle = throttle;
    this.audit = audit;
  }

  /** {@code POST /me/mfa/change { password, code }}: ลำดับข้อผิดตาม mock — รหัสผ่าน, ยังไม่ผูก, รหัสของเครื่องเดิม. */
  @Transactional
  public MfaChange start(ShopSession s, String password, String code, String ip) {
    if (auth.credentials(s, s.user().username(), password, ip).isEmpty()) {
      throw ApiException.badRequest(MfaRules.WRONG_PASSWORD);
    }
    if (!s.user().mfaEnabled()) {
      throw ApiException.badRequest(MfaRules.NOT_SET);
    }
    List<ThrottleKey> keys = List.of(ThrottleKey.shop(s.companyCode(), s.user().username()), ThrottleKey.ip(ip));
    throttle.attempt(keys);
    boolean used;
    try {
      used = mfa.use(Via.APP, s.tenantId(), s.user().id(), code == null ? "" : code);
    } catch (RuntimeException e) {
      throttle.passed(keys, false);
      throw e;
    }
    if (!used) {
      // สิทธิ์ที่จองนับเป็นความผิด
      throw ApiException.badRequest(MfaRules.WRONG_CODE);
    }
    throttle.passed(keys, false);
    return mfa.startChange(s.tenantId(), s.companyCode(), s.user());
  }

  /** {@code POST /me/mfa/change/confirm { change, code }}: รหัสแรกของเครื่องใหม่ → audit {@code mfa_enable}. */
  @Transactional
  public User confirm(ShopSession s, String change, String code) {
    String secret = mfa.changeSecret(change, s.tenantId(), s.user().id());
    if (secret == null) {
      throw ApiException.badRequest(MfaRules.CHANGE_EXPIRED);
    }
    String problem = mfa.finishChange(Via.APP, s.tenantId(), s.user().id(), secret, code == null ? "" : code);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    audit.record(s, "mfa_enable", null, "user", s.user().id(), s.user().username(), "เปลี่ยนเครื่องจากหน้าตั้งค่า");
    return sessions.userById(Via.APP, s.tenantId(), s.user().id()).map(SessionDao.UserRow::user).orElseThrow();
  }
}
