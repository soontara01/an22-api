package com.an22.api.branch;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.company.PlanLimits;
import com.an22.api.db.TenantLocks;
import com.an22.api.shift.ShiftDao;
import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.PlanRules.LimitKind;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** สาขา (E11 / E6): อ่านได้ทุกคน, เขียนโดย admin บริษัท (controller ตรวจ), ไม่มีลบ — ปิดใช้งานแทน. */
@Service
public class BranchService {

  static final String NOT_FOUND = "ไม่พบสาขา";

  private final BranchDao dao;
  private final TenantLocks locks;
  private final PlanLimits limits;
  private final Audit audit;

  private final ShiftDao shifts;

  BranchService(BranchDao dao, TenantLocks locks, PlanLimits limits, Audit audit, ShiftDao shifts) {
    this.shifts = shifts;
    this.dao = dao;
    this.locks = locks;
    this.limits = limits;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<Branch> list() {
    return dao.list();
  }

  @Transactional(readOnly = true)
  public Branch get(long id) {
    return dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
  }

  /** {@code POST /branches}: {@code id} ใน body ไม่สนใจ, ค่าที่ไม่ส่ง = ค่าเริ่ม. */
  @Transactional
  public Branch create(ShopSession s, BranchPayload in) {
    locks.lock(TenantLocks.ACCOUNTS);
    BranchPayload payload = CompanyRules.normalizeBranch(CompanyRules.branchDefaults(), in);
    String problem = CompanyRules.branchError(null, payload, dao.list());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    limits.check(s, LimitKind.BRANCHES, payload.active(), false);
    Branch branch = dao.insert(payload);
    record(s, branch, "เพิ่ม");
    return branch;
  }

  /** {@code PUT /branches/:id}: ทับเฉพาะ field ที่ส่ง. (ปิดสาขาที่มีกะเปิด → 400 มาในขั้น 8) */
  @Transactional
  public Branch update(ShopSession s, long id, BranchPayload in) {
    locks.lock(TenantLocks.ACCOUNTS);
    Branch current = dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    BranchPayload payload = CompanyRules.normalizeBranch(CompanyRules.payloadOf(current), in);
    String problem = CompanyRules.branchError(id, payload, dao.list());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    if (!payload.active()) {
      locks.lock(TenantLocks.SHIFTS);
      if (shifts.openInBranch(id)) {
        throw ApiException.badRequest("สาขา " + current.code() + " มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้");
      }
    }
    limits.check(s, LimitKind.BRANCHES, payload.active(), current.active());
    Branch branch = dao.update(id, payload);
    record(s, branch, "แก้");
    return branch;
  }

  private void record(ShopSession s, Branch b, String verb) {
    audit.record(s, "branch_change", b.id(), "branch", b.id(), b.code(),
        verb + "สาขา " + b.name() + (b.active() ? "" : " (ปิดใช้งาน)"));
  }
}
