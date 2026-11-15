package com.an22.api.platform;

import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.MfaChallenge;
import com.an22.api.model.MfaSetup;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.platform.PlatformAdminDao.AdminRow;
import com.an22.api.rules.TotpRules;
import com.an22.api.security.JwtService;
import com.an22.api.security.PiiCipher;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ส่วนของ platform login ที่อ่าน DB (platformTx) — แยกจาก {@link PlatformAuthService#login} เพื่อให้ {@code LoginThrottle} (REQUIRES_NEW,
 * pool platform) ถูกเรียกนอก transaction นี้ (ไม่ซ้อน connection จาก pool เดียวกัน — B5).
 */
@Service
class PlatformLoginTx {

  /** บัญชีในแอป Authenticator: "AN22 · PLATFORM:<username>" (ไม่มีบริษัท — {@code PLATFORM_MFA_ACCOUNT} ของหน้าบ้าน). */
  static final String MFA_ACCOUNT = "PLATFORM";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PlatformAdminDao dao;
  private final PlatformMfaDao mfa;
  private final PiiCipher cipher;
  private final JwtService jwt;
  private final ActorDao actors;
  private final Clock clock;
  private final PasswordEncoder passwords;
  /** hash ที่ไม่ตรงกับอะไรเลย — ตรวจเมื่อไม่มีชื่อนี้ เวลาตอบจะได้ไม่บอกว่ามีอยู่. */
  private final String dummyHash;

  PlatformLoginTx(
      PlatformAdminDao dao,
      PlatformMfaDao mfa,
      PiiCipher cipher,
      JwtService jwt,
      ActorDao actors,
      Clock clock,
      PasswordEncoder passwords) {
    this.dao = dao;
    this.mfa = mfa;
    this.cipher = cipher;
    this.jwt = jwt;
    this.actors = actors;
    this.clock = clock;
    this.passwords = passwords;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  /** admin ที่ชื่อ ({@code username} normalize แล้ว) + รหัสตรง — ผิด / ไม่มี = empty. */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<PlatformAdmin> verify(String username, String password) {
    Optional<AdminRow> row = username.isEmpty() ? Optional.empty() : dao.byUsername(username);
    String raw = password == null ? "" : password;
    boolean match = passwords.matches(raw, row.map(AdminRow::passwordHash).orElse(dummyHash));
    return row.isPresent() && match ? row.map(AdminRow::admin) : Optional.empty();
  }

  /** ขั้นรหัส Authenticator (E56 — ทุก admin เสมอ): {@code verify} เมื่อผูกแล้ว ไม่งั้น {@code setup} พร้อม secret ที่รอยืนยัน (เดิมถ้ามี). */
  @Transactional("platformTx")
  public MfaChallenge challenge(PlatformAdmin admin) {
    String token = jwt.issuePlatformMfa(admin.id());
    Optional<PlatformMfaDao.Row> row = mfa.find(admin.id());
    if (row.isPresent() && row.get().confirmed()) {
      return new MfaChallenge(token, "verify", null);
    }
    String secret = row.map(r -> cipher.decryptString(r.secretEnc(), aad(admin.id()))).orElse(null);
    if (secret == null) {
      actors.becomeOnPlatform(TxActor.platform(admin.id()));
      secret = TotpRules.newSecret(RANDOM);
      mfa.savePending(admin.id(), cipher.encryptString(secret, aad(admin.id())));
    }
    return new MfaChallenge(token, "setup", new MfaSetup(secret, TotpRules.otpauthUrl(MFA_ACCOUNT, admin.username(), secret)));
  }

  /** admin ของ challenge (ไม่มีแล้ว = empty). */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<PlatformAdmin> admin(long adminId) {
    return dao.byId(adminId).map(AdminRow::admin);
  }

  /** รหัสถูก (±1 ช่วง, ช่วงยังไม่เคยใช้) → ยืนยัน (ถ้าเพิ่งผูก) + จำช่วง; false = ผิด / ใช้แล้ว / ไม่มี secret. */
  @Transactional("platformTx")
  public boolean useCode(long adminId, String code) {
    Optional<PlatformMfaDao.Row> row = mfa.find(adminId);
    if (row.isEmpty()) {
      return false;
    }
    String secret = cipher.decryptString(row.get().secretEnc(), aad(adminId));
    Long step = TotpRules.verify(secret, code, clock.millis(), row.get().lastStep());
    if (step == null) {
      return false;
    }
    actors.becomeOnPlatform(TxActor.platform(adminId));
    return mfa.use(adminId, step);
  }

  static String aad(long adminId) {
    return PiiCipher.aad("dt_platform_admin_mfa", "secret", adminId);
  }
}
