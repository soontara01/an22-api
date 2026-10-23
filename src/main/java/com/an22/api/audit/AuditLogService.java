package com.an22.api.audit;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.AuditEvent;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.ListLimits;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /audit-log} (admin — controller ตรวจ, E40): admin สาขาเห็นเฉพาะเหตุการณ์ของสาขาที่ทำงานได้. */
@Service
public class AuditLogService {

  private final AuditDao dao;

  AuditLogService(AuditDao dao) {
    this.dao = dao;
  }

  @Transactional(readOnly = true)
  public List<AuditEvent> list(ShopSession s, AuditRules.Query q) {
    String problem = ListLimits.dateRangeError(q.from(), q.to());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    // canSeeAuditEvent(): canWorkIn() กับสาขาที่ถือว่าใช้งานอยู่ — สาขาที่ปิดแล้วยังเห็นเหตุการณ์เดิม
    List<Long> visible = UserRules.isCompanyAdmin(s.user()) ? null : s.user().branchIds();
    List<AuditEvent> rows = dao.list(q, visible, ListLimits.QUERY_LIMIT);
    problem = ListLimits.rowLimitError(rows.size(), true);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    return rows;
  }
}
