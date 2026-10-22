package com.an22.api.audit;

import com.an22.api.auth.ShopSession;
import org.springframework.stereotype.Component;

/** บันทึกเหตุการณ์ของผู้ใช้ร้านใน transaction ของ service (สำเร็จเท่านั้น — rollback = ไม่มีเหตุการณ์). */
@Component
public class Audit {

  private final AuditDao dao;

  Audit(AuditDao dao) {
    this.dao = dao;
  }

  /**
   * @param branchId null = ระดับบริษัท
   * @param refKind null = ไม่อ้าง record (ต้อง null พร้อม {@code refLabel})
   */
  public void record(
      ShopSession s, String action, Long branchId, String refKind, Long refId, String refLabel, String detail) {
    // terminalId: ขั้น 4 (ตรวจว่าเป็นเครื่องของบริษัท) — ตอนนี้ null
    dao.insert(new AuditEntry(
        s.tenantId(), "user", s.user().id(), s.user().name(), branchId, null, action, refKind, refId, refLabel, detail));
  }
}
