package com.an22.api.shift;

import com.an22.api.audit.Audit;
import com.an22.api.auth.AuthService;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.db.TenantLocks;
import com.an22.api.masterdata.PaymentMethodDao;
import com.an22.api.model.Branch;
import com.an22.api.model.CashMovement;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.DrawerOpen;
import com.an22.api.model.PosTerminal;
import com.an22.api.model.Shift;
import com.an22.api.model.ShiftAdjustment;
import com.an22.api.model.ShiftCount;
import com.an22.api.model.ShiftPayloads;
import com.an22.api.model.User;
import com.an22.api.pos.PosTerminalDao;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.ListLimits;
import com.an22.api.rules.Money;
import com.an22.api.rules.PosTerminalRules;
import com.an22.api.rules.PriceRules;
import com.an22.api.rules.ShiftRules;
import com.an22.api.rules.UserRules;
import com.an22.api.sale.SaleDao;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * กะขาย (docs/shift-plan.md) + ล็อกหน้าจอ POS (E48) — กฎตาม {@code handleShifts} ของ mock, ทุกกฎจาก {@link ShiftRules}. เครื่อง = {@code X-Terminal-Id}.
 * เปิดกะใต้ล็อก "shifts" (+ partial unique 1 กะเปิดต่อเครื่อง / ต่อคน); เขียนกะเดิมล็อกแถวกะก่อน.
 * <p>ยอดระบบ (expectedByMethod) = เงินทอนตั้งต้น + บิลที่ขาย / void ในกะ + เงินเข้าออก — ใบลดหนี้ / ใบเปลี่ยนเข้ามาในขั้น 10 ({@link #context}).
 */
@Service
public class ShiftService {

  static final String NOT_FOUND = "ไม่พบกะ";
  static final String OWN_ONLY = "ดูได้เฉพาะกะของตัวเอง";
  static final String CLOSE_OWNER = "ปิดกะได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ";
  static final String CLOSED = "กะนี้ปิดไปแล้ว";
  static final String OTHER_TERMINAL = "กะนี้ไม่ได้เปิดที่เครื่องนี้";

  private final ShiftDao dao;
  private final PosTerminalDao terminals;
  private final BranchDao branches;
  private final PaymentMethodDao methods;
  private final CompanyInfoDao company;
  private final DocNumbers numbers;
  private final TenantLocks locks;
  private final AuthService auth;
  private final Audit audit;
  private final Clock clock;
  private final SaleDao sales;

  ShiftService(ShiftDao dao, PosTerminalDao terminals, BranchDao branches, PaymentMethodDao methods, CompanyInfoDao company,
      DocNumbers numbers, TenantLocks locks, AuthService auth, Audit audit, Clock clock, SaleDao sales) {
    this.sales = sales;
    this.dao = dao;
    this.terminals = terminals;
    this.branches = branches;
    this.methods = methods;
    this.company = company;
    this.numbers = numbers;
    this.locks = locks;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
  }

  /** {@code GET /shifts/current}: กะเปิดของเครื่องนี้ (ไม่มี = null). */
  @Transactional(readOnly = true)
  public Shift current(Long terminalId) {
    return terminal(terminalId).flatMap(dao::openOfTerminal).map(ShiftDao.Stored::shift).orElse(null);
  }

  /** {@code POST /shifts/open}: เครื่องต้องอยู่ในทะเบียน / สาขาของ request ({@code terminalError()}), body ซ้ำเลขเครื่องได้อย่างเดียว (T2). */
  @Transactional
  public Shift open(ShopSession s, BranchContext ctx, Long terminalId, ShiftPayloads.Open in) {
    locks.lock(TenantLocks.SHIFTS);
    List<PosTerminal> registry = terminals.list();
    List<Branch> all = branches.list();
    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    badRequest(PosTerminalRules.terminalError(terminal, registry, ctx.branchId(), all));
    if (in.terminalId() != null && !in.terminalId().equals(terminalId)) {
      throw ApiException.badRequest("เลขเครื่องไม่ตรงกับเครื่องนี้ (เครื่อง " + terminalId + ")");
    }
    ShiftPayloads.Open p = new ShiftPayloads.Open(terminalId, in.openingFloat(),
        in.openingDenominations() == null ? List.of() : in.openingDenominations());
    badRequest(ShiftRules.openShiftError(p, dao.openOfTerminal(terminal).isPresent(), dao.userHasOpenShift(s.user().id())));
    // เครื่องอยู่ที่สาขาของมัน (terminalError() ตรวจแล้วว่าตรงกับสาขาของ request เมื่อส่งมา — E8 / E24)
    PosTerminal t = registry.stream().filter(x -> x.id().equals(terminal)).findFirst().orElseThrow();
    String code = all.stream().filter(b -> b.id() == t.branchId()).map(Branch::code).findFirst().orElse(Long.toString(t.branchId()));
    String shiftNo = numbers.next(CompanyRules.branchDocPrefix("SH", code, LocalDate.now(clock).toString()), 2);
    long id = dao.insert(shiftNo, terminal, t.branchId(), t.posId() == null ? "" : t.posId(), s.user().id(), s.user().name(),
        p.openingFloat(), p.openingDenominations());
    return dao.find(id).orElseThrow().shift();
  }

  /**
   * {@code GET /shifts}: กะที่เปิดในช่วงวัน (≤ 366 วัน) ของสาขาตาม {@code ?branchId=} (E23) — ไม่ใช่ admin เห็นเฉพาะของตัวเอง (P6); ใหม่สุดก่อน.
   */
  @Transactional(readOnly = true)
  public List<Shift> list(ShopSession s, BranchContext ctx, String from, String to, String branchParam, boolean openOnly) {
    return shifts(s, ctx, from, to, branchParam, openOnly).stream().map(ShiftDao.Stored::shift).toList();
  }

  /** {@code GET /shifts/variance-report}: กะในช่วงเดียวกับรายการ → {@code varianceReport()}. */
  @Transactional(readOnly = true)
  public List<ShiftRules.VarianceReportRow> varianceReport(ShopSession s, BranchContext ctx, String from, String to, String branchParam) {
    List<Shift> inRange = shifts(s, ctx, from, to, branchParam, false).stream().map(ShiftDao.Stored::shift).toList();
    List<Long> ids = inRange.stream().map(Shift::id).toList();
    return ShiftRules.varianceReport(
        inRange.stream().map(x -> new ShiftRules.ClosedShift(x.id(), x.cashier(), x.status(), x.approvalStatus(), x.counts())).toList(),
        dao.adjustmentRefs(ids), dao.drawerOpenShiftIds(ids));
  }

  /** {@code GET /shifts/last-float}: เงินที่กะปิดล่าสุดของเครื่องนี้ทิ้งไว้. */
  @Transactional(readOnly = true)
  public double lastFloat(Long terminalId) {
    return terminal(terminalId).map(dao::lastFloat).orElse(0.0);
  }

  @Transactional(readOnly = true)
  public Shift get(ShopSession s, long id) {
    return readable(s, id).shift();
  }

  /** E48: ล็อกหน้าจอ POS ของกะ — กะเปิดของเครื่องนี้. */
  @Transactional
  public Shift lock(ShopSession s, long id, Long terminalId) {
    ShiftDao.Stored st = writable(id);
    requireOpenHere(st.shift(), terminalId);
    dao.setLocked(id, true);
    return dao.find(id).orElseThrow().shift();
  }

  /** E48: ปลดล็อกด้วยรหัสของเจ้าของกะหรือ admin — รหัสผิด 400 (ข้อความเดียวกับ auth/verify, นับใน LoginThrottle). */
  @Transactional
  public Shift unlock(ShopSession s, long id, ShiftPayloads.Credentials body, String ip) {
    ShiftDao.Stored st = writable(id);
    User who = auth.credentials(s, body == null ? null : body.username(), body == null ? null : body.password(), ip)
        .orElseThrow(() -> ApiException.badRequest(AuthService.WRONG_LOGIN));
    if (who.id() != st.cashierId() && !UserRules.hasAdminRights(who.role())) {
      throw ApiException.badRequest("ปลดล็อกได้เฉพาะ " + st.shift().cashier() + " หรือผู้ดูแลระบบ");
    }
    dao.setLocked(id, false);
    return dao.find(id).orElseThrow().shift();
  }

  @Transactional(readOnly = true)
  public List<CashMovement> movements(ShopSession s, long id) {
    readable(s, id);
    return dao.movements(id);
  }

  @Transactional(readOnly = true)
  public List<DrawerOpen> drawerOpens(ShopSession s, long id) {
    readable(s, id);
    return dao.drawerOpens(id);
  }

  @Transactional(readOnly = true)
  public List<ShiftAdjustment> adjustments(ShopSession s, long id) {
    readable(s, id);
    return dao.adjustments(id);
  }

  /** {@code GET /shifts/:id/summary}: ยอดต่อช่องทางเฉพาะ admin หรือกะที่ปิดแล้ว (blind count, S2). */
  @Transactional(readOnly = true)
  public ShiftRules.ShiftSummary summary(ShopSession s, long id) {
    Shift shift = readable(s, id).shift();
    boolean detailed = isAdmin(s) || "closed".equals(shift.status());
    return ShiftRules.shiftSummary(shift.id(), shift.openingFloat(), context(shift.id()), detailed);
  }

  /** เงินเข้า / ออกลิ้นชัก: กะเปิดของเครื่องนี้, {@code shiftBlocker('cash_movement')}, {@code cashMovementError()}. */
  @Transactional
  public CashMovement cashMovement(ShopSession s, long id, Long terminalId, ShiftPayloads.CashMove in) {
    ShiftDao.Stored st = writable(id);
    Shift shift = st.shift();
    requireOpenHere(shift, terminalId);
    ShiftPayloads.CashMove p = new ShiftPayloads.CashMove(in.type(), in.amount(), in.reason() == null ? "" : in.reason().strip());
    String problem = ShiftRules.shiftBlocker("cash_movement", true, ref(st), s.user().id(), isAdmin(s));
    if (problem == null) {
      problem = ShiftRules.cashMovementError(p,
          ShiftRules.drawerExpected(ShiftRules.expectedByMethod(id, shift.openingFloat(), context(id))), isAdmin(s));
    }
    badRequest(problem);
    long movement = dao.insertMovement(id, p.type(), p.amount(), p.reason(), s.user().name());
    return dao.movements(id).stream().filter(m -> m.id() == movement).findFirst().orElseThrow();
  }

  /** เปิดลิ้นชักโดยไม่มีการขาย (S7): กติกาผู้ทำ / เครื่องเดียวกับเงินเข้าออก + ต้องมีเหตุผล. */
  @Transactional
  public DrawerOpen drawerOpen(ShopSession s, long id, Long terminalId, ShiftPayloads.DrawerOpenReason in) {
    ShiftDao.Stored st = writable(id);
    requireOpenHere(st.shift(), terminalId);
    String reason = in == null || in.reason() == null ? "" : in.reason().strip();
    String problem = ShiftRules.shiftBlocker("drawer_open", true, ref(st), s.user().id(), isAdmin(s));
    badRequest(problem != null ? problem : ShiftRules.drawerOpenError(reason));
    long opened = dao.insertDrawerOpen(id, reason, s.user().name());
    return dao.drawerOpens(id).stream().filter(d -> d.id() == opened).findFirst().orElseThrow();
  }

  /** {@code POST /shifts/:id/count-check}: เปิดยอดระบบหลังนับครบ (S2) — ไม่บันทึกอะไร (เหมือน mock); ไม่ตรวจเครื่อง. */
  @Transactional(readOnly = true)
  public ShiftRules.CountCheckResult countCheck(ShopSession s, long id, ShiftPayloads.Counts body) {
    Shift shift = closable(s, id, false).shift();
    if (!"open".equals(shift.status())) {
      throw ApiException.badRequest(CLOSED);
    }
    List<ShiftPayloads.CountInput> counts = body == null || body.counts() == null ? List.of() : body.counts();
    List<ShiftRules.ExpectedLine> expected = ShiftRules.expectedByMethod(id, shift.openingFloat(), context(id));
    badRequest(ShiftRules.countInputsError(expected, counts));
    return ShiftRules.countCheck(expected, counts, varianceLimit());
  }

  /**
   * {@code POST /shifts/:id/close}: {@code closeShiftError()} แล้วผู้อนุมัติ = admin ที่ปิดเอง หรือ login ของ admin ที่พิมพ์ที่เครื่อง; ส่วนต่างเกิน
   * เพดานโดยไม่มีผู้อนุมัติ = pending. ไม่ตรวจเครื่อง (admin ปิดแทนได้ — audit {@code shift_close_other}).
   */
  @Transactional
  public Shift close(ShopSession s, long id, ShiftPayloads.Close in, String ip) {
    ShiftDao.Stored st = closable(s, id, true);
    Shift shift = st.shift();
    ShiftPayloads.Close p = new ShiftPayloads.Close(in.counts() == null ? List.of() : in.counts(),
        in.cashDenominations() == null ? List.of() : in.cashDenominations(), in.depositAmount() == null ? 0.0 : in.depositAmount(),
        in.note() == null ? "" : in.note().strip(), in.approval());
    List<ShiftRules.ExpectedLine> expected = ShiftRules.expectedByMethod(id, shift.openingFloat(), context(id));
    badRequest(ShiftRules.closeShiftError(ref(st), p, expected, s.user().id(), isAdmin(s)));
    String approver = isAdmin(s) ? s.user().name() : null;
    if (approver == null && p.approval() != null) {
      User found = auth.credentials(s, p.approval().username(), p.approval().password(), ip)
          .orElseThrow(() -> ApiException.badRequest("รหัสผ่านผู้อนุมัติไม่ถูกต้อง"));
      if (!UserRules.hasAdminRights(found.role())) {
        throw ApiException.badRequest("ผู้อนุมัติต้องเป็นผู้ดูแลระบบ");
      }
      approver = found.name();
    }
    List<ShiftCount> counts = ShiftRules.buildShiftCounts(expected, p.counts(), s.user().name());
    ShiftRules.ExpectedLine drawer = expected.stream().filter(l -> "cash".equals(l.type())).findFirst().orElse(null);
    double cash = drawer == null ? 0
        : counts.stream().filter(c -> c.methodId() == drawer.methodId() && c.counted() != null).map(ShiftCount::counted).findFirst()
            .orElse(0.0);
    String approval = ShiftRules.approvalStatusFor(counts, varianceLimit(), approver);
    dao.close(id, s.user().name(), counts, p.cashDenominations(), p.depositAmount(), Money.round2(cash - p.depositAmount()), approval,
        "approved".equals(approval) ? approver : null, p.note());
    if (st.cashierId() != s.user().id()) {
      audit.record(s, "shift_close_other", shift.branchId(), "shift", id, shift.shiftNo(), "กะของ " + shift.cashier());
    }
    return dao.find(id).orElseThrow().shift();
  }

  /** {@code POST /shifts/:id/approve} (admin — controller ตรวจ): เฉพาะกะที่รออนุมัติ. */
  @Transactional
  public Shift approve(ShopSession s, long id) {
    Shift shift = dao.lock(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND)).shift();
    if (!"pending".equals(shift.approvalStatus())) {
      throw ApiException.badRequest("กะนี้ไม่ได้รออนุมัติ");
    }
    dao.approve(id, s.user().name());
    audit.record(s, "shift_approve", shift.branchId(), "shift", id, shift.shiftNo(), "กะของ " + shift.cashier());
    return dao.find(id).orElseThrow().shift();
  }

  /** {@code POST /shifts/:id/adjustments} (admin — controller ตรวจ): บันทึกแยก ยอดนับ / Z report / การอนุมัติไม่เปลี่ยน (S8). */
  @Transactional
  public ShiftAdjustment adjust(ShopSession s, long id, ShiftPayloads.Adjust in) {
    Shift shift = dao.lock(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND)).shift();
    ShiftPayloads.Adjust p = new ShiftPayloads.Adjust(in.methodId(), in.amount(), in.reasonCode(),
        in.reason() == null ? "" : in.reason().strip());
    badRequest(ShiftRules.shiftAdjustmentError(shift.status(), shift.counts(), p, isAdmin(s)));
    String name = shift.counts().stream().filter(c -> c.methodId() == p.methodId()).map(ShiftCount::name).findFirst().orElse("");
    long adjustment = dao.insertAdjustment(id, p.methodId(), name, p.amount(), p.reasonCode(), p.reason(), s.user().name());
    audit.record(s, "shift_adjust", shift.branchId(), "shift", id, shift.shiftNo(),
        (p.amount() > 0 ? "+" : "") + PriceRules.jsNumber(p.amount()) + " — " + p.reason());
    return dao.adjustments(id).stream().filter(a -> a.id() == adjustment).findFirst().orElseThrow();
  }

  /** {@code requireShiftChangeError()} สำหรับ PUT /settings/store|company. */
  @Transactional(readOnly = true)
  public String requireShiftChangeError(boolean current, boolean next) {
    return ShiftRules.requireShiftChangeError(current, next, current && !next ? dao.openCount() : 0);
  }

  // ---------- ภายใน ----------

  /** ทุกสิ่งที่เดินเงินในกะ: บิลที่ขาย / void ในกะ + เงินเข้าออก — ใบลดหนี้ / ใบเปลี่ยนเติมในขั้น 10. */
  private ShiftRules.ShiftContext context(long shiftId) {
    List<ShiftRules.MovementRef> moves = dao.movements(shiftId).stream()
        .map(m -> new ShiftRules.MovementRef(m.shiftId(), m.type(), m.amount())).toList();
    return new ShiftRules.ShiftContext(sales.shiftRefs(shiftId), List.of(), moves, methods.list(), List.of(),
        dao.drawerOpenShiftIds(List.of(shiftId)));
  }

  private List<ShiftDao.Stored> shifts(ShopSession s, BranchContext ctx, String from, String to, String branchParam, boolean openOnly) {
    ListLimits.ParsedRange parsed = ListLimits.parseRange(from, to);
    badRequest(parsed.error());
    List<Branch> all = branches.list();
    long working = ctx.branchId() != null ? ctx.branchId() : all.getFirst().id();
    Object scope = UserRules.parseBranchScope(branchParam, working);
    if (scope == null) {
      throw ApiException.badRequest("รหัสสาขาไม่ถูกต้อง");
    }
    String refused = UserRules.branchScopeError(scope, s.user(), all);
    if (refused != null) {
      throw ApiException.forbidden(refused);
    }
    List<ShiftDao.Stored> rows = dao.list(parsed.range().from(), parsed.range().to(), "all".equals(scope) ? null : (Long) scope,
        isAdmin(s) ? null : s.user().id(), openOnly, ListLimits.QUERY_LIMIT);
    badRequest(ListLimits.rowLimitError(rows.size(), true));
    return rows;
  }

  /** กะของคนอื่น: ดูได้เฉพาะ admin (P6). */
  private ShiftDao.Stored readable(ShopSession s, long id) {
    ShiftDao.Stored st = dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    if (st.cashierId() != s.user().id() && !isAdmin(s)) {
      throw ApiException.forbidden(OWN_ONLY);
    }
    return st;
  }

  /** นับ / ปิดกะของคนอื่น: เฉพาะ admin (P6) — {@code lock} = ล็อกแถวกะ (งานเขียน; count-check อ่านอย่างเดียว). */
  private ShiftDao.Stored closable(ShopSession s, long id, boolean lock) {
    ShiftDao.Stored st = (lock ? dao.lock(id) : dao.find(id)).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    if (st.cashierId() != s.user().id() && !isAdmin(s)) {
      throw ApiException.forbidden(CLOSE_OWNER);
    }
    return st;
  }

  /** งานเขียนอื่นของกะ (ล็อก / เงินเข้าออก / ลิ้นชัก): ล็อกแถวกะ — สิทธิ์ตามกฎของแต่ละงาน. */
  private ShiftDao.Stored writable(long id) {
    return dao.lock(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
  }

  private static void requireOpenHere(Shift shift, Long terminalId) {
    if (!"open".equals(shift.status())) {
      throw ApiException.badRequest(CLOSED);
    }
    if (terminalId == null || shift.terminalId() != terminalId) {
      throw ApiException.badRequest(OTHER_TERMINAL);
    }
  }

  private static ShiftRules.ShiftRef ref(ShiftDao.Stored st) {
    return new ShiftRules.ShiftRef(st.shift().status(), st.cashierId(), st.shift().cashier());
  }

  private double varianceLimit() {
    CompanyInfo info = company.get();
    return info.shiftVarianceLimit() == null ? 0 : info.shiftVarianceLimit().doubleValue();
  }

  private static Optional<Integer> terminal(Long terminalId) {
    return terminalId == null || terminalId > Integer.MAX_VALUE ? Optional.empty() : Optional.of(terminalId.intValue());
  }

  private static boolean isAdmin(ShopSession s) {
    return UserRules.hasAdminRights(s.user().role());
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
