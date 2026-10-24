package com.an22.api.auth;

import com.an22.api.auth.SessionDao.Via;
import com.an22.api.billing.BillingService;
import com.an22.api.model.LoginResponse;
import com.an22.api.model.User;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * login ร้าน (D4: รหัสบริษัท + ชื่อผู้ใช้ + รหัสผ่าน) และตรวจรหัสโดยไม่สร้าง session ({@code auth/verify}) — ลำดับ / ข้อความตาม mock.
 * ผิดทุกกรณีข้อความเดียว (E3); บริษัทถูกระงับบอกเฉพาะเมื่อรหัสผ่านถูก.
 */
@Service
public class AuthService {

  public static final String WRONG_COMPANY_LOGIN = "รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง";
  public static final String WRONG_LOGIN = "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง";
  /** บอกหลังรหัสผ่านถูกเท่านั้น ตามเหตุที่ระงับ. */
  static final Map<String, String> SUSPENDED_MESSAGE =
      Map.of(
          "platform", "บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ",
          "overdue", "บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน",
          "deletion", "บริษัทนี้อยู่ระหว่างรอลบข้อมูล — ติดต่อเจ้าของบริษัท");

  private final LoginThrottle throttle;
  private final ShopLoginTx tx;
  private final BillingService billing;

  AuthService(LoginThrottle throttle, ShopLoginTx tx, BillingService billing) {
    this.throttle = throttle;
    this.tx = tx;
    this.billing = billing;
  }

  public record LoginRequest(String companyCode, String username, String password) {}

  public record VerifyRequest(String username, String password) {}

  /**
   * {@code POST /auth/login}: ไม่เปิด transaction เอง — จองสิทธิ์ลอง ({@link LoginThrottle#attempt}, B6) / billing catch-up /
   * {@link ShopLoginTx#attempt} / ผล เป็น transaction สั้น ๆ ต่อกัน ไม่ซ้อน: 1 request ใช้ connection ของ pool platform ทีละ 1 (B5).
   */
  public LoginResponse login(LoginRequest req, String ip) {
    String code = CompanyRules.normalizeCompanyCode(req.companyCode());
    String username = UserRules.normalizeUsername(req.username());
    List<ThrottleKey> keys = List.of(ThrottleKey.shop(code, username), ThrottleKey.ip(ip));
    throttle.attempt(keys);
    boolean wrong = false;
    boolean ok = false;
    try {
      // ออกใบแจ้งหนี้ / ระงับค้างชำระที่ถึงรอบก่อน job (แผนหัวข้อ "Stateless / scale") — commit ของตัวเองแม้ login ไม่ผ่าน
      tx.companyId(code).ifPresent(billing::catchUp);
      LoginResponse response = tx.attempt(code, username, req.password());
      ok = true;
      return response;
    } catch (ShopLoginTx.Rejected rejected) {
      wrong = true;
      throttle.failed(rejected.failedLogin);
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_COMPANY_LOGIN);
    } finally {
      // รหัสถูก (สำเร็จ / บริษัทถูกระงับ) หรือ error = ไม่ใช่ความผิด → คืนสิทธิ์ที่จอง
      if (!wrong) {
        throttle.passed(keys, ok);
      }
    }
  }

  /**
   * {@code POST /auth/verify}: รหัสของผู้ใช้ในบริษัทของ token (ปลดล็อก POS, ผู้อนุมัติ) — ผิดทุกกรณี 400 ข้อความเดียว (ห้าม 401: จะ
   * logout คนหน้าเครื่อง).
   */
  @Transactional(readOnly = true)
  public User verify(ShopSession session, VerifyRequest req, String ip) {
    return credentials(session, req.username(), req.password(), ip).orElseThrow(() -> ApiException.badRequest(WRONG_LOGIN));
  }

  /**
   * รหัสของผู้ใช้ในบริษัทของ session (ปลดล็อก POS, ผู้อนุมัติส่วนต่าง — E48) — นับผิดใน {@link LoginThrottle} แบบเดียวกับ login
   * (ล็อกอยู่ → 429); ผิด = empty (ผู้เรียกเลือกข้อความ 400 เอง). ทำงานใน transaction ของผู้เรียก (appTx) — throttle ใช้ pool platform
   * (คนละ pool จึงไม่ติดตายแบบ B5; ห้ามเรียกจาก platformTx).
   */
  public Optional<User> credentials(ShopSession session, String usernameIn, String password, String ip) {
    String username = UserRules.normalizeUsername(usernameIn == null ? "" : usernameIn);
    List<ThrottleKey> keys = List.of(ThrottleKey.shop(session.companyCode(), username), ThrottleKey.ip(ip));
    throttle.attempt(keys);
    boolean wrong = false;
    try {
      Optional<User> user = tx.check(Via.APP, session.tenantId(), username, password);
      wrong = user.isEmpty();
      return user;
    } finally {
      // ผิด = สิทธิ์ที่จองนับเป็นความผิด; ถูก (ไม่ล้างตัวนับของบัญชี — เหมือนเดิม) / error = คืนสิทธิ์
      if (!wrong) {
        throttle.passed(keys, false);
      }
    }
  }
}
