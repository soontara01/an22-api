package com.an22.api.rules;

/**
 * port จาก {@code core/models/mfa.model.ts} (E55): login สองขั้นด้วย Microsoft Authenticator — ข้อความไทยตรงทุกตัวอักษร.
 */
public final class MfaRules {

  public static final String WRONG_CODE = "รหัสยืนยันไม่ถูกต้อง";
  public static final String CHALLENGE_EXPIRED = "หมดเวลายืนยันรหัส กรุณาเข้าสู่ระบบใหม่";

  private MfaRules() {}

  /** {@code mfaRequiredFor()}: บริษัทบังคับผู้ใช้นี้. */
  public static boolean mfaRequiredFor(String role, String companyRule) {
    String rule = companyRule == null ? "none" : companyRule;
    return "all".equals(rule) || ("admins".equals(rule) && UserRules.hasAdminRights(role));
  }

  /** {@code loginNeedsMfa()}: ผ่าน {@code /auth/login-mfa} หรือผู้ใช้มีแล้ว หรือบริษัทบังคับ — ข้ามไม่ได้. */
  public static boolean loginNeedsMfa(String role, boolean mfaEnabled, String companyRule, boolean asked) {
    return asked || mfaEnabled || mfaRequiredFor(role, companyRule);
  }

  /** {@code mfaDisableError()}: ปิดของตัวเอง. */
  public static String mfaDisableError(String role, boolean mfaEnabled, String companyRule) {
    if (!mfaEnabled) {
      return "ยังไม่ได้เปิดใช้ Microsoft Authenticator";
    }
    if (mfaRequiredFor(role, companyRule)) {
      return "บริษัทบังคับให้ใช้ Microsoft Authenticator — ปิดเองไม่ได้";
    }
    return null;
  }

  /** {@code mfaResetError()}: admin ล้างของผู้อื่น. */
  public static String mfaResetError(long targetId, boolean targetMfaEnabled, long callerId) {
    if (targetId == callerId) {
      return "ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ปิดได้ที่หน้าตั้งค่า";
    }
    if (!targetMfaEnabled) {
      return "ผู้ใช้นี้ยังไม่ได้เปิดใช้ Microsoft Authenticator";
    }
    return null;
  }
}
