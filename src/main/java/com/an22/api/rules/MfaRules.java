package com.an22.api.rules;

/**
 * port จาก {@code core/models/mfa.model.ts}: login สองขั้นด้วย Microsoft Authenticator — ผู้ใช้ร้านทุกคนต้องใส่รหัส 6 หลักทุกครั้ง (E57),
 * platform admin ด้วย (E56); เปลี่ยนเครื่องเองตอน login อยู่ / platform ล้างให้ผู้ใช้ร้าน (E58). ข้อความไทยตรงทุกตัวอักษร.
 */
public final class MfaRules {

  public static final String WRONG_CODE = "รหัสยืนยันไม่ถูกต้อง";
  public static final String CHALLENGE_EXPIRED = "หมดเวลายืนยันรหัส กรุณาเข้าสู่ระบบใหม่";
  /** E58: หลักฐานเปลี่ยนเครื่อง ({@code change}) ไม่มี / หมดอายุ / ของคนอื่น / ใช้ไปแล้ว. */
  public static final String CHANGE_EXPIRED = "หมดเวลาเปลี่ยนเครื่อง กรุณาเริ่มใหม่";
  public static final String WRONG_PASSWORD = "รหัสผ่านไม่ถูกต้อง";
  public static final String NOT_SET = "ผู้ใช้นี้ยังไม่ได้ผูก Microsoft Authenticator";
  /** E58: platform ล้างให้ผู้ใช้ของบริษัทตามชื่อผู้ใช้. */
  public static final String USER_NOT_FOUND = "ไม่พบชื่อผู้ใช้นี้ในบริษัท";

  private MfaRules() {}

  /** {@code mfaResetError()}: admin ล้างของผู้อื่น (มือถือหาย) — ผูกใหม่ตอน login ครั้งถัดไป. */
  public static String mfaResetError(long targetId, boolean targetMfaEnabled, long callerId) {
    if (targetId == callerId) {
      return "ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ให้ผู้ดูแลระบบคนอื่นล้างให้";
    }
    if (!targetMfaEnabled) {
      return NOT_SET;
    }
    return null;
  }
}
