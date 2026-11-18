package com.an22.api.auth;

import com.an22.api.auth.SessionDao.CompanyRow;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.config.ApiProperties;
import com.an22.api.mail.Mailer;
import com.an22.api.rules.SignupRules;
import com.an22.api.security.PiiCipher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ส่วนของลืมรหัสผ่านที่อยู่ใน platformTx — แยกจาก {@link AccountService} เพื่อให้ตัวนับ ({@link LoginThrottle}, REQUIRES_NEW, pool
 * platform) ถูกเรียกนอก transaction นี้ (ไม่ซ้อน connection จาก pool เดียวกัน — B5).
 */
@Service
class AccountTx {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AccountDao dao;
  private final SessionDao sessionDao;
  private final PiiCipher cipher;
  private final Mailer mailer;
  private final String baseUrl;
  private final Clock clock;

  AccountTx(AccountDao dao, SessionDao sessionDao, PiiCipher cipher, Mailer mailer, ApiProperties props, Clock clock) {
    this.dao = dao;
    this.sessionDao = sessionDao;
    this.cipher = cipher;
    this.mailer = mailer;
    this.baseUrl = props.app().baseUrl().replaceAll("/+$", "");
    this.clock = clock;
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
