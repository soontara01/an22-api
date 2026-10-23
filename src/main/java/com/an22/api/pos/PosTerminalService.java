package com.an22.api.pos;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.PlanLimits;
import com.an22.api.db.TenantLocks;
import com.an22.api.shift.ShiftDao;
import com.an22.api.model.Branch;
import com.an22.api.model.PosTerminal;
import com.an22.api.rules.PlanRules.LimitKind;
import com.an22.api.rules.PosTerminalRules;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ทะเบียนเครื่อง POS (T1 / T5 / E8) — เขียน: admin บริษัท หรือ admin ของสาขาของเครื่อง (ย้ายสาขาต้องมีสิทธิ์ทั้งสองสาขา). ปิดเครื่องที่มีกะเปิด /
 * ลบเครื่องที่มีกะ → 400 (เอกสารการขายเติมในขั้น 9).
 */
@Service
public class PosTerminalService {

  static final String NOT_FOUND = "ไม่พบเครื่อง POS";
  static final String BRANCH_ONLY = "แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง";

  private final PosTerminalDao dao;
  private final BranchDao branches;
  private final TenantLocks locks;
  private final PlanLimits limits;
  private final Audit audit;

  private final ShiftDao shifts;

  PosTerminalService(PosTerminalDao dao, BranchDao branches, TenantLocks locks, PlanLimits limits, Audit audit, ShiftDao shifts) {
    this.shifts = shifts;
    this.dao = dao;
    this.branches = branches;
    this.locks = locks;
    this.limits = limits;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<PosTerminal> list() {
    return dao.list();
  }

  @Transactional
  public PosTerminal create(ShopSession s, PosTerminal in) {
    locks.lock(TenantLocks.ACCOUNTS);
    PosTerminal t = PosTerminalRules.normalize(PosTerminalRules.defaults(), in);
    List<Branch> all = branches.list();
    if (notMine(s, all, t.branchId())) {
      throw ApiException.forbidden(BRANCH_ONLY);
    }
    badRequest(PosTerminalRules.posTerminalError(t, dao.list(), all));
    limits.check(s, LimitKind.TERMINALS, t.active(), false);
    dao.insert(t);
    record(s, t, "เพิ่ม");
    return t;
  }

  /** เลขเครื่องเปลี่ยนไม่ได้ (เอกสาร / กะอ้างอยู่) — ทับเฉพาะ field ที่ส่ง. */
  @Transactional
  public PosTerminal update(ShopSession s, int id, PosTerminal in) {
    locks.lock(TenantLocks.ACCOUNTS);
    PosTerminal current = dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    PosTerminal next = PosTerminalRules.normalize(current, new PosTerminal(id, in.name(), in.posId(), in.branchId(), in.active()));
    List<Branch> all = branches.list();
    if (notMine(s, all, current.branchId()) || notMine(s, all, next.branchId())) {
      throw ApiException.forbidden(BRANCH_ONLY);
    }
    badRequest(PosTerminalRules.posTerminalError(next, dao.list().stream().filter(t -> t.id() != id).toList(), all));
    if (!next.active()) {
      locks.lock(TenantLocks.SHIFTS);
      if (shifts.openOfTerminal(id).isPresent()) {
        throw ApiException.badRequest("เครื่อง " + id + " มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้");
      }
    }
    limits.check(s, LimitKind.TERMINALS, next.active(), current.active());
    dao.update(next);
    record(s, next, "แก้");
    return next;
  }

  @Transactional
  public void delete(ShopSession s, int id) {
    PosTerminal current = dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    if (notMine(s, branches.list(), current.branchId())) {
      throw ApiException.forbidden(BRANCH_ONLY);
    }
    // มีกะ (ขั้น 9: + บิล / ใบลดหนี้ / ใบเปลี่ยน) = เอกสารอ้างอยู่
    if (shifts.terminalUsed(id)) {
      throw ApiException.badRequest("เครื่อง " + id + " มีกะหรือเอกสารแล้ว ลบไม่ได้ (ปิดใช้งานแทน)");
    }
    dao.delete(id);
    audit.record(s, "terminal_change", null, "terminal", (long) id, "เครื่อง " + id, "ลบเครื่อง");
  }

  /** admin สาขาแก้ได้เฉพาะเครื่องของสาขาที่ตัวเองทำงานได้ (E6) — สาขาที่ไม่มีอยู่ ให้กฎ 400 ตอบแทน. */
  private static boolean notMine(ShopSession s, List<Branch> all, Long branchId) {
    Branch branch = all.stream().filter(b -> branchId != null && b.id() == branchId).findFirst().orElse(null);
    return !UserRules.isCompanyAdmin(s.user()) && branch != null && !UserRules.canWorkIn(s.user(), branch);
  }

  private void record(ShopSession s, PosTerminal t, String verb) {
    audit.record(s, "terminal_change", t.branchId(), "terminal", (long) t.id(), "เครื่อง " + t.id(),
        verb + "เครื่อง " + t.name() + " · POS ID " + (t.posId().isEmpty() ? "-" : t.posId()) + (t.active() ? "" : " (ปิดใช้งาน)"));
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
