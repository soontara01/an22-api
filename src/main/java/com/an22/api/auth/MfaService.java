package com.an22.api.auth;

import com.an22.api.auth.SessionDao.Via;
import com.an22.api.model.MfaChallenge;
import com.an22.api.model.MfaSetup;
import com.an22.api.model.User;
import com.an22.api.rules.TotpRules;
import com.an22.api.security.JwtService;
import com.an22.api.security.PiiCipher;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * secret ของ Microsoft Authenticator (E55): เข้ารหัสด้วย {@link PiiCipher} (AAD {@code dt_user_mfa.secret:<tenant>}), ตรวจรหัสด้วย
 * {@link TotpRules} ตาม {@code Clock} ของแอป. ทำงานใน transaction ของผู้เรียก ({@code Via} ต้องตรง).
 */
@Service
public class MfaService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final MfaDao dao;
  private final PiiCipher cipher;
  private final JwtService jwt;
  private final Clock clock;

  MfaService(MfaDao dao, PiiCipher cipher, JwtService jwt, Clock clock) {
    this.dao = dao;
    this.cipher = cipher;
    this.jwt = jwt;
    this.clock = clock;
  }

  /** ขั้นรหัสของ login: {@code verify} เมื่อเปิดใช้แล้ว ไม่งั้น {@code setup} พร้อม secret ที่รอยืนยัน. ต้องตั้ง TxActor แล้ว (stamp). */
  MfaChallenge challenge(Via via, long tenantId, String companyCode, User user) {
    String token = jwt.issueMfa(tenantId, user.id());
    if (user.mfaEnabled()) {
      return new MfaChallenge(token, "verify", null);
    }
    return new MfaChallenge(token, "setup", pendingSetup(via, tenantId, companyCode, user));
  }

  /** secret ที่รอยืนยันของผู้ใช้ (เดิมถ้ามี — ผู้ใช้อาจสแกนไปแล้ว) หรือสร้างใหม่. */
  public MfaSetup pendingSetup(Via via, long tenantId, String companyCode, User user) {
    String secret = dao.find(via, tenantId, user.id())
        .filter(row -> !row.confirmed())
        .map(row -> cipher.decryptString(row.secretEnc(), aad(tenantId)))
        .orElse(null);
    if (secret == null) {
      secret = TotpRules.newSecret(RANDOM);
      dao.savePending(via, tenantId, user.id(), cipher.encryptString(secret, aad(tenantId)));
    }
    return new MfaSetup(secret, TotpRules.otpauthUrl(companyCode, user.username(), secret));
  }

  /** รหัสถูก (±1 ช่วง, ยังไม่เคยใช้ช่วงนี้) → เปิดใช้ถ้ายังไม่เปิด + จำช่วง; false = ผิด / ไม่มี secret / ใช้ไปแล้ว. */
  public boolean use(Via via, long tenantId, long userId, String code) {
    MfaDao.Row row = dao.find(via, tenantId, userId).orElse(null);
    if (row == null) {
      return false;
    }
    String secret = cipher.decryptString(row.secretEnc(), aad(tenantId));
    Long step = TotpRules.verify(secret, code, clock.millis(), row.lastStep());
    return step != null && dao.use(via, tenantId, userId, step);
  }

  /** มี secret (ยืนยันแล้วหรือไม่ก็ตาม) — ลบแล้ว true. */
  public boolean remove(Via via, long tenantId, long userId) {
    return dao.delete(via, tenantId, userId);
  }

  /** มี secret ที่รอยืนยันไหม. */
  public boolean hasPending(Via via, long tenantId, long userId) {
    return dao.find(via, tenantId, userId).filter(row -> !row.confirmed()).isPresent();
  }

  static String aad(long tenantId) {
    return PiiCipher.aad("dt_user_mfa", "secret", tenantId);
  }
}
