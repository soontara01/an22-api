package com.an22.api.audit;

/**
 * 1 เหตุการณ์ของ audit log (E40, ตาราง action ใน domain-rules.md) — {@code at} = เวลาที่ insert.
 *
 * @param userId null = ไม่ใช่ผู้ใช้ของบริษัท (platform / system / login ผิด)
 * @param userName ชื่อ ณ ตอนนั้น (login ผิด = ชื่อที่พิมพ์)
 * @param branchId null = ระดับบริษัท
 * @param terminalId เครื่อง POS ของบริษัทนี้เท่านั้น ไม่งั้น null
 */
public record AuditEntry(
    long tenantId,
    String actorKind,
    Long userId,
    String userName,
    Long branchId,
    Long terminalId,
    String action,
    String refKind,
    Long refId,
    String refLabel,
    String detail) {

  /** เหตุการณ์ของผู้ใช้ร้านที่ไม่อ้าง record (login, menu_access …). */
  public static AuditEntry ofUser(long tenantId, Long userId, String userName, Long terminalId, String action, String detail) {
    return new AuditEntry(tenantId, "user", userId, userName, null, terminalId, action, null, null, null, detail);
  }
}
