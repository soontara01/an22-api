package com.an22.api.auth;

import com.an22.api.auth.SessionDao.Via;
import com.an22.api.model.MfaChallenge;
import com.an22.api.model.MfaChange;
import com.an22.api.model.MfaSetup;
import com.an22.api.model.User;
import com.an22.api.rules.MfaRules;
import com.an22.api.rules.TotpRules;
import com.an22.api.security.JwtService;
import com.an22.api.security.PiiCipher;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
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

  /**
   * E58 ขั้นที่ 1 (ผู้เรียกตรวจรหัสผ่าน + รหัสของเครื่องเดิมแล้ว): secret ใหม่ที่ยังไม่บันทึก — อยู่ใน {@code change} (JWT) จนรหัสแรกของ
   * เครื่องใหม่ถูก; เครื่องเดิมใช้ได้ต่อจนถึงตอนนั้น.
   */
  public MfaChange startChange(long tenantId, String companyCode, User user) {
    String secret = TotpRules.newSecret(RANDOM);
    String enc = Base64.getEncoder().encodeToString(cipher.encryptString(secret, changeAad(tenantId)));
    return new MfaChange(jwt.issueMfaChange(tenantId, user.id(), enc),
        new MfaSetup(secret, TotpRules.otpauthUrl(companyCode, user.username(), secret)));
  }

  /** secret ใหม่ใน {@code change} ของผู้ใช้นี้ — ไม่ผ่าน / ของคนอื่น / หมดอายุ = null. */
  public String changeSecret(String change, long tenantId, long userId) {
    try {
      var token = jwt.decodeMfaChange(change).orElse(null);
      if (token == null || ((Number) token.getClaim(JwtService.CLAIM_TENANT)).longValue() != tenantId
          || Long.parseLong(token.getSubject()) != userId) {
        return null;
      }
      return cipher.decryptString(Base64.getDecoder().decode(token.getClaimAsString(JwtService.CLAIM_SECRET)), changeAad(tenantId));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * E58 ขั้นที่ 2: รหัสแรกของเครื่องใหม่ถูก → แทน secret เดิม. {@code CHANGE_EXPIRED} เมื่อถูกล้างไประหว่างนั้น หรือ secret นี้ใช้อยู่แล้ว
   * (ส่ง change เดิมซ้ำ); {@code WRONG_CODE} เมื่อรหัสผิด. null = เปลี่ยนแล้ว.
   */
  public String finishChange(Via via, long tenantId, long userId, String secret, String code) {
    MfaDao.Row row = dao.find(via, tenantId, userId).filter(MfaDao.Row::confirmed).orElse(null);
    if (row == null || secret.equals(cipher.decryptString(row.secretEnc(), aad(tenantId)))) {
      return MfaRules.CHANGE_EXPIRED;
    }
    Long step = TotpRules.verify(secret, code, clock.millis(), null);
    if (step == null) {
      return MfaRules.WRONG_CODE;
    }
    return dao.replace(via, tenantId, userId, cipher.encryptString(secret, aad(tenantId)), step) ? null : MfaRules.CHANGE_EXPIRED;
  }

  /** มี secret (ยืนยันแล้วหรือไม่ก็ตาม) — ลบแล้ว true. */
  public boolean remove(Via via, long tenantId, long userId) {
    return dao.delete(via, tenantId, userId);
  }

  static String aad(long tenantId) {
    return PiiCipher.aad("dt_user_mfa", "secret", tenantId);
  }

  /** secret ใน {@code change} (E58) เข้ารหัสด้วย AAD ของตัวเอง — ใช้แทน secret_enc ไม่ได้. */
  static String changeAad(long tenantId) {
    return PiiCipher.aad("mfa_change", "secret", tenantId);
  }
}
