package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * จำกัด login ผิด (ตกลง 2026-10-22): ต่อบัญชี 5 ครั้ง / 15 นาที, ต่อ X-Real-IP 30 ครั้ง / 15 นาที → ล็อก 15 นาที, ตอบ 429.
 * ใช้กับ login ร้าน / platform, {@code auth/verify}, ปลดล็อก POS (ขั้น 8).
 *
 * <p>ทุก method เป็น transaction ของตัวเอง ({@code REQUIRES_NEW}): การนับผิด + audit {@code login_failed} ต้องบันทึกแม้ request
 * ตอบ error (rollback).
 */
@Service
public class LoginThrottle {

  public static final String TOO_MANY = "พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่";
  static final int ACCOUNT_MAX = 5;
  static final int IP_MAX = 30;
  static final int WINDOW_MINUTES = 15;
  static final int LOCK_MINUTES = 15;

  private final LoginThrottleDao dao;
  private final AuditDao audit;
  private final PiiCipher cipher;

  LoginThrottle(LoginThrottleDao dao, AuditDao audit, PiiCipher cipher) {
    this.dao = dao;
    this.audit = audit;
    this.cipher = cipher;
  }

  /** @throws ApiException 429 เมื่อ key ใดถูกล็อกอยู่ (ตรวจก่อนดูรหัสผ่าน — ถูกก็ไม่ผ่านระหว่างล็อก) */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public void check(List<ThrottleKey> keys) {
    for (ThrottleKey key : keys) {
      if (dao.locked(cipher.hmac(key.value()))) {
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
      }
    }
  }

  /** นับผิดทุก key (+ audit {@code login_failed} ถ้ามี) ใน transaction ที่ commit เสมอ. */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void fail(List<ThrottleKey> keys, AuditEntry failedLogin) {
    for (ThrottleKey key : keys) {
      int max = "ip".equals(key.scope()) ? IP_MAX : ACCOUNT_MAX;
      dao.fail(cipher.hmac(key.value()), key.scope(), max, WINDOW_MINUTES, LOCK_MINUTES);
    }
    if (failedLogin != null) {
      audit.insertOnPlatform(failedLogin);
    }
  }

  /**
   * นับการเรียก endpoint public ที่ไม่ได้ตรวจรหัส (สมัครใช้งาน, ลืมรหัสผ่าน) — นับทุกครั้งไม่ว่าผลเป็นอย่างไร; ครบ {@code max} ของ key ใน
   * {@value #WINDOW_MINUTES} นาที → ล็อก {@value #LOCK_MINUTES} นาที. ผู้เรียก {@link #check} ก่อน.
   */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void count(Map<ThrottleKey, Integer> maxByKey) {
    maxByKey.forEach((key, max) -> dao.fail(cipher.hmac(key.value()), key.scope(), max, WINDOW_MINUTES, LOCK_MINUTES));
  }

  /** login สำเร็จ → ล้างตัวนับของบัญชี (ของ IP ไม่ล้าง). */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void succeed(ThrottleKey account) {
    dao.reset(cipher.hmac(account.value()));
  }
}
