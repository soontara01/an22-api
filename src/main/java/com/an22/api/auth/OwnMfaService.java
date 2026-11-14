package com.an22.api.auth;

import com.an22.api.audit.Audit;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.model.MfaSetup;
import com.an22.api.model.User;
import com.an22.api.rules.MfaRules;
import com.an22.api.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ผู้ใช้เปิด / ปิด Microsoft Authenticator ของตัวเองจากหน้าตั้งค่า (E55, {@code handleOwnMfa()} ของ mock) — appTx ของ session.
 */
@Service
public class OwnMfaService {

  static final String ALREADY_ON = "เปิดใช้ Microsoft Authenticator อยู่แล้ว";
  static final String NO_PENDING = "กรุณาเริ่มตั้งค่า Microsoft Authenticator ใหม่";
  static final String WRONG_PASSWORD = "รหัสผ่านไม่ถูกต้อง";

  private final MfaService mfa;
  private final SessionDao sessions;
  private final CompanyInfoDao company;
  private final AuthService auth;
  private final Audit audit;

  OwnMfaService(MfaService mfa, SessionDao sessions, CompanyInfoDao company, AuthService auth, Audit audit) {
    this.mfa = mfa;
    this.sessions = sessions;
    this.company = company;
    this.auth = auth;
    this.audit = audit;
  }

  /** {@code POST /me/mfa/setup}: secret ที่รอยืนยัน (เดิมถ้ามี). */
  @Transactional
  public MfaSetup setup(ShopSession s) {
    if (s.user().mfaEnabled()) {
      throw ApiException.badRequest(ALREADY_ON);
    }
    return mfa.pendingSetup(Via.APP, s.tenantId(), s.companyCode(), s.user());
  }

  /** {@code POST /me/mfa/confirm { code }}: รหัสแรกที่ถูกเปิดใช้. */
  @Transactional
  public User confirm(ShopSession s, String code) {
    if (s.user().mfaEnabled()) {
      throw ApiException.badRequest(ALREADY_ON);
    }
    if (!mfa.hasPending(Via.APP, s.tenantId(), s.user().id())) {
      throw ApiException.badRequest(NO_PENDING);
    }
    if (!mfa.use(Via.APP, s.tenantId(), s.user().id(), code)) {
      throw ApiException.badRequest(MfaRules.WRONG_CODE);
    }
    audit.record(s, "mfa_enable", null, "user", s.user().id(), s.user().username(), "เปิดใช้จากหน้าตั้งค่า");
    return me(s);
  }

  /**
   * {@code POST /me/mfa/disable { password }}: รหัสผ่านของตัวเอง (นับผิดใน LoginThrottle เหมือน auth/verify) แล้ว {@code mfaDisableError()}.
   */
  @Transactional
  public User disable(ShopSession s, String password, String ip) {
    if (auth.credentials(s, s.user().username(), password, ip).isEmpty()) {
      throw ApiException.badRequest(WRONG_PASSWORD);
    }
    String problem = MfaRules.mfaDisableError(s.user().role(), s.user().mfaEnabled(), company.get().mfaRequired());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    mfa.remove(Via.APP, s.tenantId(), s.user().id());
    audit.record(s, "mfa_disable", null, "user", s.user().id(), s.user().username(), "ปิดใช้จากหน้าตั้งค่า");
    return me(s);
  }

  private User me(ShopSession s) {
    return sessions.userById(Via.APP, s.tenantId(), s.user().id()).map(SessionDao.UserRow::user).orElseThrow();
  }
}
