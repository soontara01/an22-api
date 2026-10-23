package com.an22.api.audit;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.AuditEvent;
import com.an22.api.rules.AuditRules;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /audit-log?from=&to=&action=&userId=&branchId=} — ใหม่สุดก่อน. */
@RestController
public class AuditLogController {

  private final AuditLogService service;

  AuditLogController(AuditLogService service) {
    this.service = service;
  }

  @GetMapping("/audit-log")
  List<AuditEvent> list(ShopSession s, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String action, @RequestParam(required = false) String userId,
      @RequestParam(required = false) String branchId) {
    Permissions.requireAdmin(s, "ดูประวัติการใช้งานได้เฉพาะผู้ดูแลระบบ");
    return service.list(s, AuditRules.parseAuditQuery(from, to, action, userId, branchId));
  }
}
