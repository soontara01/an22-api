package com.an22.api.auth;

import com.an22.api.audit.AuditDao;
import com.an22.api.audit.AuditEntry;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import java.util.LinkedHashMap;
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
 * <p>ลำดับ: {@link #attempt} (จองสิทธิ์ก่อนตรวจรหัส) → ผิด {@link #failed} / ไม่ผิด {@link #passed}. ทุก method เป็น transaction ของตัวเอง
 * ({@code REQUIRES_NEW}) — การนับ + audit {@code login_failed} ต้องบันทึกแม้ request ตอบ error; ห้ามเรียกจากใน platformTx (B5).
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

  /**
   * จองสิทธิ์ลอง 1 ครั้งของทุก key <b>ก่อน</b>ตรวจรหัส (atomic — check แล้วค่อยนับ ทำให้ burst พร้อมกันลองเกินเพดานได้: B6).
   * @throws ApiException 429 เมื่อ key ใดล็อกอยู่ / ครบเพดาน (rollback — ไม่จอง key ใดเลย; รหัสถูกก็ไม่ผ่านระหว่างล็อก)
   */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void attempt(List<ThrottleKey> keys) {
    Map<ThrottleKey, Integer> maxByKey = new LinkedHashMap<>();
    keys.forEach(key -> maxByKey.put(key, max(key)));
    reserve(maxByKey);
  }

  /** รหัสผิด: สิทธิ์ที่จองไว้นับเป็นความผิดแล้ว — บันทึก audit {@code login_failed} (ถ้ามี) ใน transaction ที่ commit เสมอ. */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void failed(AuditEntry failedLogin) {
    if (failedLogin != null) {
      audit.insertOnPlatform(failedLogin);
    }
  }

  /**
   * ลองแล้วไม่ใช่ความผิด (รหัสถูก / บริษัทถูกระงับ / error): คืนสิทธิ์ทุก key; {@code resetAccount} = login สำเร็จ → ล้างตัวนับของบัญชี
   * (ของ IP ไม่ล้าง).
   */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void passed(List<ThrottleKey> keys, boolean resetAccount) {
    for (ThrottleKey key : keys) {
      if (resetAccount && "account".equals(key.scope())) {
        dao.reset(cipher.hmac(key.value()));
      } else {
        dao.release(cipher.hmac(key.value()), max(key));
      }
    }
  }

  /**
   * นับการเรียก endpoint public ที่ไม่ได้ตรวจรหัส (สมัครใช้งาน, ลืมรหัสผ่าน) — นับทุกครั้งไม่ว่าผลเป็นอย่างไร (ไม่คืน); ครบ {@code max} ของ key
   * ใน {@value #WINDOW_MINUTES} นาที → ล็อก {@value #LOCK_MINUTES} นาที.
   * @throws ApiException 429 เมื่อ key ใดล็อกอยู่ / ครบเพดาน (atomic เหมือน {@link #attempt})
   */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public void count(Map<ThrottleKey, Integer> maxByKey) {
    reserve(maxByKey);
  }

  private void reserve(Map<ThrottleKey, Integer> maxByKey) {
    for (Map.Entry<ThrottleKey, Integer> e : maxByKey.entrySet()) {
      if (!dao.reserve(cipher.hmac(e.getKey().value()), e.getKey().scope(), e.getValue(), WINDOW_MINUTES, LOCK_MINUTES)) {
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
      }
    }
  }

  private static int max(ThrottleKey key) {
    return "ip".equals(key.scope()) ? IP_MAX : ACCOUNT_MAX;
  }
}
