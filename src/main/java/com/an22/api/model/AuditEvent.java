package com.an22.api.model;

import java.time.Instant;

/** {@code AuditEvent} (audit.model.ts, E40): ref null = ไม่อ้าง record. */
public record AuditEvent(
    long id, Instant at, String actorKind, Long userId, String userName, Long branchId, Long terminalId, String action, Ref ref,
    String detail) {

  /** {@code AuditRef}: label = เลขเอกสาร / SKU / ชื่อผู้ใช้ ณ ตอนนั้น. */
  public record Ref(String kind, Long id, String label) {}
}
