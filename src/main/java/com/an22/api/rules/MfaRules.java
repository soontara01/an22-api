package com.an22.api.rules;

/**
 * port จาก {@code core/models/mfa.model.ts}: login สองขั้นด้วย Microsoft Authenticator — ผู้ใช้ร้านทุกคนต้องใส่รหัส 6 หลักทุกครั้ง (E57),
 * platform admin ด้วย (E56). ข้อความไทยตรงทุกตัวอักษร.
 */
public final class MfaRules {

  public static final String WRONG_CODE = "รหัสยืนยันไม่ถูกต้อง";
  public static final String CHALLENGE_EXPIRED = "หมดเวลายืนยันรหัส กรุณาเข้าสู่ระบบใหม่";

  private MfaRules() {}

  /** {@code mfaResetError()}: admin ล้างของผู้อื่น (มือถือหาย) — ผูกใหม่ตอน login ครั้งถัดไป. */
  public static String mfaResetError(long targetId, boolean targetMfaEnabled, long callerId) {
    if (targetId == callerId) {
      return "ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ให้ผู้ดูแลระบบคนอื่นล้างให้";
    }
    if (!targetMfaEnabled) {
      return "ผู้ใช้นี้ยังไม่ได้ผูก Microsoft Authenticator";
    }
    return null;
  }
}
