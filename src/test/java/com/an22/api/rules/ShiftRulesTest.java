package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.DenominationCount;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.SalePayment;
import com.an22.api.model.ShiftCount;
import com.an22.api.model.ShiftPayloads.CashMove;
import com.an22.api.model.ShiftPayloads.Close;
import com.an22.api.model.ShiftPayloads.CountInput;
import com.an22.api.model.ShiftPayloads.Open;
import com.an22.api.rules.ShiftRules.AdjustmentRef;
import com.an22.api.rules.ShiftRules.ClosedShift;
import com.an22.api.rules.ShiftRules.CreditNoteRef;
import com.an22.api.rules.ShiftRules.ExpectedLine;
import com.an22.api.rules.ShiftRules.MovementRef;
import com.an22.api.rules.ShiftRules.SaleRef;
import com.an22.api.rules.ShiftRules.ShiftContext;
import com.an22.api.rules.ShiftRules.ShiftRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ตาม {@code shift.model.spec.ts} — ผู้ใช้ในตัวอย่าง: somchai (id 1, เจ้าของกะ), suda (id 2), admin (id 3). ไม่ port normalizeShift / shiftDay /
 * shiftOpenTooLong (หน้าบ้านเท่านั้น).
 */
class ShiftRulesTest {

  private static final long SOMCHAI = 1;
  private static final long SUDA = 2;
  private static final long ADMIN = 3;

  private static PaymentMethod method(long id, String name, String type, Boolean active, String closeCheck) {
    return new PaymentMethod(id, "M" + id, name, type, active, (int) id, false, "", 0.0, null, 0.0, "none", "", "", List.of(),
        closeCheck, "");
  }

  private static PaymentMethod method(long id, String name, String type) {
    return method(id, name, type, true, null);
  }

  private static final List<PaymentMethod> METHODS =
      List.of(method(1, "เงินสด", "cash"), method(2, "บัตร", "card"), method(3, "QR", "qr"), method(4, "โอน", "transfer"));
  private static final PaymentMethod CASH = METHODS.get(0);
  private static final PaymentMethod CARD = METHODS.get(1);
  private static final PaymentMethod QR = METHODS.get(2);

  private static SalePayment pay(PaymentMethod m, double amount) {
    return new SalePayment(m.id(), m.name(), m.type(), amount, amount, "", null);
  }

  private static SaleRef sale(List<SalePayment> payments, Long shiftId, Long voidShiftId, double itemDiscount) {
    return new SaleRef(shiftId, voidShiftId, payments, payments.stream().mapToDouble(SalePayment::amount).sum(), itemDiscount, 0, 0);
  }

  private static SaleRef sale(SalePayment... payments) {
    return sale(List.of(payments), 1L, null, 0);
  }

  /** docs/shift-plan.md หัวข้อ 3: float 2,000 · เงินสด 5,350 · บัตร 12,900 · QR 3,500 · CN 450 · เงินออก 200 */
  private static final ShiftContext CTX = new ShiftContext(
      List.of(sale(pay(CASH, 5350)), sale(List.of(pay(CARD, 12900)), 1L, null, 100), sale(pay(QR, 3500))),
      List.of(new CreditNoteRef(1L, List.of(pay(CASH, 450)), 450)),
      List.of(new MovementRef(1, "out", 200)),
      METHODS,
      List.of(1L, 2L),
      List.of());
  private static final ShiftRef SHIFT = new ShiftRef("open", SOMCHAI, "somchai");
  private static final List<ExpectedLine> EXPECTED = ShiftRules.expectedByMethod(1, 2000, CTX);

  private static CountInput input(long methodId) {
    return new CountInput(methodId, null, null, false, null, "");
  }

  private static CountInput counted(long methodId, double amount) {
    return new CountInput(methodId, amount, null, false, null, "");
  }

  private static CountInput with(CountInput c, Double firstCount, String reasonCode, String reason) {
    return new CountInput(c.methodId(), c.counted(), firstCount, c.confirmed(), reasonCode, reason);
  }

  private static List<CountInput> counts(CountInput... overrides) {
    List<CountInput> base = new ArrayList<>(List.of(counted(1, 6700), counted(2, 12900), counted(3, 3500),
        new CountInput(4L, null, null, true, null, "")));
    for (CountInput o : overrides) {
      base.replaceAll(c -> c.methodId().equals(o.methodId()) ? o : c);
    }
    return base;
  }

  private static Close close(List<CountInput> counts) {
    return new Close(counts, List.of(), 0.0, "", null);
  }

  private static Close close(List<DenominationCount> notes, double deposit) {
    return new Close(counts(), notes, deposit, "", null);
  }

  private static String closeError(Close p, List<ExpectedLine> expected) {
    return ShiftRules.closeShiftError(SHIFT, p, expected, SOMCHAI, false);
  }

  @Test
  void computesTheExpectedAmountPerMethod() {
    assertThat(EXPECTED).extracting(ExpectedLine::name, ExpectedLine::expected, ExpectedLine::closeCheck).containsExactly(
        org.assertj.core.groups.Tuple.tuple("เงินสด", 6700.0, "count"), org.assertj.core.groups.Tuple.tuple("บัตร", 12900.0, "count"),
        org.assertj.core.groups.Tuple.tuple("QR", 3500.0, "count"), org.assertj.core.groups.Tuple.tuple("โอน", 0.0, "confirm"));
    ExpectedLine drawer = EXPECTED.getFirst();
    assertThat(List.of(drawer.opening(), drawer.sales(), drawer.refunds(), drawer.cashIn(), drawer.cashOut()))
        .containsExactly(2000.0, 5350.0, 450.0, 0.0, 200.0);
    assertThat(ShiftRules.drawerExpected(EXPECTED)).isEqualTo(6700);
  }

  @Test
  void countsAVoidInTheShiftWhereItIsMade() {
    SaleRef sold = sale(List.of(pay(CASH, 1000)), 1L, 2L, 0);
    ShiftContext c = new ShiftContext(List.of(sold), List.of(), List.of(), METHODS, List.of(), List.of());
    assertThat(ShiftRules.drawerExpected(ShiftRules.expectedByMethod(1, 0, c))).isEqualTo(1000);
    assertThat(ShiftRules.drawerExpected(ShiftRules.expectedByMethod(2, 0, c))).isEqualTo(-1000);
    ShiftContext same = new ShiftContext(List.of(sale(List.of(pay(CASH, 1000)), 1L, 1L, 0)), List.of(), List.of(), METHODS, List.of(),
        List.of());
    assertThat(ShiftRules.drawerExpected(ShiftRules.expectedByMethod(1, 0, same))).isEqualTo(0);
  }

  @Test
  void putsEveryCashMethodInOneDrawerAndKeepsInactiveMethodsThatMovedMoney() {
    PaymentMethod spare = method(5, "เงินสดสำรอง", "cash");
    PaymentMethod cheque = method(6, "เช็ค", "other", false, null);
    List<PaymentMethod> all = new ArrayList<>(METHODS);
    all.add(spare);
    all.add(cheque);
    ShiftContext c = new ShiftContext(List.of(sale(pay(CASH, 100), pay(spare, 50)), sale(pay(cheque, 500))), List.of(),
        List.of(new MovementRef(1, "in", 20)), all, List.of(), List.of());
    List<ExpectedLine> lines = ShiftRules.expectedByMethod(1, 1000, c);
    assertThat(lines.stream().filter(l -> "cash".equals(l.type()))).hasSize(1);
    assertThat(lines.getFirst()).extracting(ExpectedLine::methodId, ExpectedLine::sales, ExpectedLine::cashIn, ExpectedLine::expected)
        .containsExactly(1L, 150.0, 20.0, 1170.0);
    assertThat(lines.stream().filter(l -> l.methodId() == 6).findFirst().orElseThrow())
        .extracting(ExpectedLine::expected, ExpectedLine::closeCheck).containsExactly(500.0, "confirm");
  }

  @Test
  void ignoresMoneyOfOtherShiftsAndBillsWithoutAShift() {
    ShiftContext c = new ShiftContext(List.of(sale(List.of(pay(CASH, 999)), 2L, null, 0), sale(List.of(pay(CASH, 77)), null, null, 0)),
        CTX.creditNotes(), CTX.movements(), METHODS, List.of(), List.of());
    assertThat(ShiftRules.drawerExpected(ShiftRules.expectedByMethod(1, 2000, c))).isEqualTo(2000 - 450 - 200);
  }

  @Test
  void acceptsAMatchingBlindCountAndAsksForAReasonOnAVariance() {
    assertThat(closeError(close(counts()), EXPECTED)).isNull();
    CountInput isShort = counted(1, 6680);
    assertThat(closeError(close(counts(isShort)), EXPECTED)).isEqualTo("เงินสด ขาด 20 บาท — กรุณาระบุสาเหตุ");
    assertThat(closeError(close(counts(with(isShort, null, "wrong_change", ""))), EXPECTED)).isNull();
    assertThat(closeError(close(counts(with(isShort, null, "other", ""))), EXPECTED)).isEqualTo("กรุณาอธิบายสาเหตุส่วนต่างของ เงินสด");
  }

  @Test
  void checksEveryLineByItsCloseCheck() {
    assertThat(closeError(close(counts(input(2))), EXPECTED)).isEqualTo("กรุณากรอกยอดที่นับได้ของ บัตร");
    assertThat(closeError(close(counts(input(4))), EXPECTED)).isEqualTo("กรุณาตรวจยอด โอน แล้วติ๊ก \"ตรวจแล้ว\"");
    assertThat(closeError(close(counts(counted(1, 6700.005))), EXPECTED)).isEqualTo("กรุณากรอกยอดที่นับได้ของ เงินสด");
    List<PaymentMethod> noCheckMethods = METHODS.stream()
        .map(m -> m.id() == 4 ? method(4, "โอน", "transfer", true, "none") : m).toList();
    List<ExpectedLine> noCheck = ShiftRules.expectedByMethod(1, 2000,
        new ShiftContext(CTX.sales(), CTX.creditNotes(), CTX.movements(), noCheckMethods, List.of(), List.of()));
    assertThat(closeError(close(counts(input(4))), noCheck)).isNull();
  }

  @Test
  void countsANonCashMethodNetOfRefundsBelowZero() {
    ShiftContext refundCtx = new ShiftContext(
        List.of(sale(pay(CARD, 1000)), sale(List.of(pay(QR, 500)), 2L, 1L, 0)),
        List.of(new CreditNoteRef(1L, List.of(pay(CARD, 25160)), 25160)), List.of(), METHODS, List.of(), List.of());
    List<ExpectedLine> lines = ShiftRules.expectedByMethod(1, 1000, refundCtx);
    assertThat(lines).extracting(ExpectedLine::expected).containsExactly(1000.0, -24160.0, -500.0, 0.0);
    List<CountInput> c = List.of(counted(1, 1000), with(counted(2, -24160), -24000.0, null, ""), counted(3, -500),
        new CountInput(4L, null, null, true, null, ""));
    assertThat(ShiftRules.countInputsError(lines, c)).isNull();
    ShiftRules.CountCheckResult check = ShiftRules.countCheck(lines, c, 100);
    assertThat(check.lines()).extracting(ShiftRules.CountCheckLine::variance).containsExactly(0.0, 0.0, 0.0, 0.0);
    assertThat(check.needsApproval()).isFalse();
    assertThat(closeError(close(c), lines)).isNull();
    List<CountInput> negativeCash = new ArrayList<>(c.subList(1, 4));
    negativeCash.add(counted(1, -1));
    assertThat(ShiftRules.countInputsError(lines, negativeCash)).isEqualTo("กรุณากรอกยอดที่นับได้ของ เงินสด");
    assertThat(ShiftRules.countInputsError(lines, List.of(c.get(0), counted(2, -24160.005), c.get(2), c.get(3))))
        .isEqualTo("กรุณากรอกยอดที่นับได้ของ บัตร");
    assertThat(ShiftRules.countInputsError(lines, List.of(with(c.get(0), -1.0, null, ""), c.get(1), c.get(2), c.get(3))))
        .isEqualTo("ยอดนับครั้งแรกของ เงินสด ไม่ถูกต้อง");
  }

  private static DenominationCount note(double value, double qty) {
    return new DenominationCount(value, qty);
  }

  @Test
  void checksTheBanknoteCountAndTheDeposit() {
    List<DenominationCount> notes = List.of(note(1000, 6), note(500, 1), note(100, 2));
    assertThat(closeError(close(notes, 0), EXPECTED)).isNull();
    assertThat(closeError(close(notes.subList(0, 2), 0), EXPECTED)).isEqualTo("ยอดรวมแบงก์/เหรียญไม่ตรงกับยอดเงินสดที่นับ");
    assertThat(closeError(close(List.of(note(3, 1)), 0), EXPECTED)).isEqualTo("จำนวนแบงก์/เหรียญไม่ถูกต้อง");
    assertThat(closeError(close(List.of(), 4700), EXPECTED)).isNull();
    assertThat(closeError(close(List.of(), 6701), EXPECTED)).isEqualTo("เงินนำส่งต้องอยู่ระหว่าง 0 ถึงยอดเงินสดที่นับได้");
  }

  @Test
  void letsOnlyTheCashierOrAnAdminCloseAnOpenShift() {
    assertThat(ShiftRules.closeShiftError(new ShiftRef("closed", SOMCHAI, "somchai"), close(counts()), EXPECTED, SOMCHAI, false))
        .isEqualTo("กะนี้ปิดไปแล้ว");
    assertThat(ShiftRules.closeShiftError(SHIFT, close(counts()), EXPECTED, SUDA, false)).isEqualTo("ปิดกะได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ");
    assertThat(ShiftRules.closeShiftError(SHIFT, close(counts()), EXPECTED, ADMIN, true)).isNull();
  }

  private static ShiftCount count(Double counted, double expected) {
    return new ShiftCount(1, "", expected, counted, "count", null, null, null, "");
  }

  @Test
  void needsApprovalWhenAnySingleMethodIsOffByMoreThanTheLimit() {
    List<ShiftCount> offset = List.of(count(6620.0, 6700), count(12980.0, 12900));
    assertThat(ShiftRules.needsApproval(offset, 100)).isFalse();
    assertThat(ShiftRules.approvalStatusFor(offset, 100, null)).isEqualTo("not_needed");
    List<ShiftCount> isShort = List.of(count(6550.0, 6700));
    assertThat(ShiftRules.needsApproval(isShort, 100)).isTrue();
    assertThat(ShiftRules.approvalStatusFor(isShort, 100, null)).isEqualTo("pending");
    assertThat(ShiftRules.approvalStatusFor(isShort, 100, "admin")).isEqualTo("approved");
    assertThat(ShiftRules.needsApproval(List.of(count(null, 9999)), 100)).isFalse();
  }

  @Test
  void buildsTheStoredCounts() {
    List<ShiftCount> stored = ShiftRules.buildShiftCounts(EXPECTED,
        counts(with(counted(1, 6680), 6600.0, "unknown", " ? "), with(counted(2, 12900), null, "other", "ignored")), "somchai");
    assertThat(stored.get(0)).extracting(ShiftCount::expected, ShiftCount::counted, ShiftCount::firstCount, ShiftCount::reasonCode,
        ShiftCount::reason).containsExactly(6700.0, 6680.0, 6600.0, "unknown", "?");
    assertThat(stored.get(1)).extracting(ShiftCount::counted, ShiftCount::reasonCode, ShiftCount::reason)
        .containsExactly(12900.0, null, "");
    assertThat(stored.get(3)).extracting(ShiftCount::counted, ShiftCount::closeCheck, ShiftCount::confirmedBy)
        .containsExactly(null, "confirm", "somchai");
  }

  @Test
  void validatesCashMovementsAndTellsOnlyAdminsTheExpectedCash() {
    CashMove out = new CashMove("out", 200.0, "ค่าส่งของ");
    assertThat(ShiftRules.cashMovementError(out, 6700, false)).isNull();
    assertThat(ShiftRules.cashMovementError(new CashMove("in", 7000.0, "ค่าส่งของ"), 6700, false)).isNull();
    assertThat(ShiftRules.cashMovementError(new CashMove("out", 0.0, "ค่าส่งของ"), 6700, false))
        .isEqualTo("จำนวนเงินต้องมากกว่า 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)");
    assertThat(ShiftRules.cashMovementError(new CashMove("out", 200.0, " "), 6700, false)).isEqualTo("กรุณาระบุเหตุผล");
    CashMove tooMuch = new CashMove("out", 7000.0, "ค่าส่งของ");
    assertThat(ShiftRules.cashMovementError(tooMuch, 6700, false)).isEqualTo("เงินสดในลิ้นชักไม่พอ").doesNotContainPattern("\\d");
    assertThat(ShiftRules.cashMovementError(tooMuch, 6700, true)).isEqualTo("เงินสดในลิ้นชักไม่พอ (คาดว่ามี 6,700 บาท)");
  }

  @Test
  void validatesOpeningAShift() {
    Open p = new Open(1L, 2000.0, List.of());
    assertThat(ShiftRules.openShiftError(p, false, false)).isNull();
    assertThat(ShiftRules.openShiftError(new Open(0L, 2000.0, List.of()), false, false)).isEqualTo("ไม่ได้ระบุเครื่อง POS");
    assertThat(ShiftRules.openShiftError(new Open(1L, -1.0, List.of()), false, false)).isEqualTo("เงินทอนตั้งต้นต้องไม่ติดลบ");
    assertThat(ShiftRules.openShiftError(new Open(1L, 1000.005, List.of()), false, false))
        .isEqualTo("เงินทอนตั้งต้นทศนิยมไม่เกิน 2 ตำแหน่ง");
    assertThat(ShiftRules.openShiftError(new Open(1L, Double.NaN, List.of()), false, false)).isEqualTo("กรุณากรอกเงินทอนตั้งต้น");
    assertThat(ShiftRules.openShiftError(new Open(1L, 2000.0, List.of(note(1000, 1))), false, false))
        .isEqualTo("ยอดรวมแบงก์/เหรียญไม่ตรงกับเงินทอนตั้งต้น");
    assertThat(ShiftRules.openShiftError(new Open(1L, 2000.0, List.of(note(1000, 2))), false, false)).isNull();
    assertThat(ShiftRules.openShiftError(p, true, false)).isEqualTo("เครื่องนี้มีกะที่เปิดอยู่แล้ว");
    assertThat(ShiftRules.openShiftError(p, false, true)).isEqualTo("คุณมีกะที่เปิดอยู่ที่เครื่องอื่น — ปิดกะนั้นก่อน");
    assertThat(ShiftRules.denominationTotal(List.of(note(1000, 2), note(0.25, 3)))).isEqualTo(2000.75);
  }

  @Test
  void blocksMoneyActionsWithoutTheRightShift() {
    assertThat(ShiftRules.shiftBlocker("sell", false, null, SOMCHAI, false)).isNull();
    assertThat(ShiftRules.shiftBlocker("sell", true, null, SOMCHAI, false)).isEqualTo("ยังไม่ได้เปิดกะที่เครื่องนี้");
    assertThat(ShiftRules.shiftBlocker("sell", true, new ShiftRef("closed", SOMCHAI, "somchai"), SOMCHAI, false))
        .isEqualTo("ยังไม่ได้เปิดกะที่เครื่องนี้");
    assertThat(ShiftRules.shiftBlocker("sell", true, SHIFT, SOMCHAI, false)).isNull();
    assertThat(ShiftRules.shiftBlocker("sell", true, SHIFT, ADMIN, true)).isEqualTo("กะนี้เป็นของ somchai — ต้องปิดกะนั้นก่อนจึงจะขายได้");
    assertThat(ShiftRules.shiftBlocker("void", true, SHIFT, ADMIN, true)).isNull();
    assertThat(ShiftRules.shiftBlocker("refund", true, SHIFT, ADMIN, true)).isNull();
    assertThat(ShiftRules.shiftBlocker("void", true, SHIFT, SUDA, false))
        .isEqualTo("กะนี้เป็นของ somchai — ทำรายการนี้ได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ");
    assertThat(ShiftRules.shiftBlocker("cash_movement", true, SHIFT, SUDA, false)).isNotNull();
  }

  @Test
  void turnsShiftsOffOnlyWhenNoneIsOpen() {
    assertThat(ShiftRules.requireShiftChangeError(true, false, 2)).isEqualTo("ยังมีกะที่เปิดอยู่ 2 กะ — ปิดกะทั้งหมดก่อนปิดการใช้ระบบกะ");
    assertThat(ShiftRules.requireShiftChangeError(true, false, 0)).isNull();
    assertThat(ShiftRules.requireShiftChangeError(false, true, 3)).isNull();
  }

  @Test
  void summarizesAShiftAndHidesThePerMethodLinesUnlessDetailed() {
    ShiftRules.ShiftSummary x = ShiftRules.shiftSummary(1, 2000, CTX, false);
    assertThat(List.of(x.billCount(), x.voidCount(), x.refundCount(), x.exchangeCount())).containsExactly(3, 0, 1, 1);
    assertThat(List.of(x.salesTotal(), x.discountTotal(), x.refundTotal(), x.cashIn(), x.cashOut(), x.netSales()))
        .containsExactly(21750.0, 100.0, 450.0, 0.0, 200.0, 21300.0);
    assertThat(x.lines()).isNull();
    assertThat(x.checklist()).isEqualTo(ShiftRules.closeChecklist(EXPECTED));
    assertThat(ShiftRules.shiftSummary(1, 2000, CTX, true).lines()).isEqualTo(EXPECTED);
  }

  @Test
  void revealsVariancesOnlyFromACompleteCount() {
    List<CountInput> c = counts(counted(1, 6680));
    assertThat(ShiftRules.countInputsError(EXPECTED, c)).isNull();
    assertThat(ShiftRules.countInputsError(EXPECTED, counts(input(2)))).isEqualTo("กรุณากรอกยอดที่นับได้ของ บัตร");
    ShiftRules.CountCheckResult check = ShiftRules.countCheck(EXPECTED, c, 100);
    assertThat(check.needsApproval()).isFalse();
    assertThat(check.lines().get(0)).extracting(ShiftRules.CountCheckLine::expected, ShiftRules.CountCheckLine::counted,
        ShiftRules.CountCheckLine::variance).containsExactly(6700.0, 6680.0, -20.0);
    assertThat(check.lines().get(3)).extracting(ShiftRules.CountCheckLine::closeCheck, ShiftRules.CountCheckLine::counted,
        ShiftRules.CountCheckLine::variance).containsExactly("confirm", null, 0.0);
    assertThat(ShiftRules.countCheck(EXPECTED, counts(counted(1, 6550)), 100).needsApproval()).isTrue();
  }

  private static ClosedShift closedShift(long id, String cashier, String status, String approval, double... diffs) {
    List<ShiftCount> counts = new ArrayList<>();
    for (int i = 0; i < diffs.length; i++) {
      counts.add(new ShiftCount(i + 1, "", 1000, 1000 + diffs[i], "count", null, null, null, ""));
    }
    return new ClosedShift(id, cashier, status, approval, counts);
  }

  @Test
  void reportsVariancesPerCashierOverClosedShifts() {
    List<ShiftRules.VarianceReportRow> rows = ShiftRules.varianceReport(List.of(
        closedShift(1, "somchai", "closed", "not_needed", -50, 0), closedShift(2, "somchai", "closed", "not_needed", -50, 30),
        closedShift(3, "suda", "closed", "pending", -150), closedShift(4, "suda", "open", "not_needed", -999)), List.of(), List.of());
    assertThat(rows).containsExactly(
        new ShiftRules.VarianceReportRow("suda", 1, 1, 0, 150, 0, 1, 0),
        new ShiftRules.VarianceReportRow("somchai", 2, 2, 1, 100, 30, 0, 0));
  }

  @Test
  void logsADrawerOpenedWithoutASaleWithAReasonAndCountsIt() {
    assertThat(ShiftRules.drawerOpenError(" ")).isEqualTo("กรุณาระบุเหตุผลที่เปิดลิ้นชัก");
    assertThat(ShiftRules.drawerOpenError("x".repeat(201))).isEqualTo("เหตุผลต้องไม่เกิน 200 ตัวอักษร");
    assertThat(ShiftRules.drawerOpenError("แลกเหรียญ / แลกแบงก์")).isNull();
    assertThat(ShiftRules.shiftBlocker("drawer_open", true, SHIFT, SOMCHAI, false)).isNull();
    assertThat(ShiftRules.shiftBlocker("drawer_open", true, SHIFT, ADMIN, true)).isNull();
    assertThat(ShiftRules.shiftBlocker("drawer_open", true, SHIFT, SUDA, false))
        .isEqualTo("กะนี้เป็นของ somchai — ทำรายการนี้ได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ");
    List<ShiftRules.VarianceReportRow> rows =
        ShiftRules.varianceReport(List.of(closedShift(1, "somchai", "closed", "not_needed", 0)), List.of(), List.of(1L, 1L, 2L));
    assertThat(rows.getFirst().drawerOpens()).isEqualTo(2);
  }

  @Test
  void adjustsAClosedShiftSeparatelyWithoutTouchingItsCounts() {
    ClosedShift shift = closedShift(1, "somchai", "closed", "not_needed", -100, 0);
    com.an22.api.model.ShiftPayloads.Adjust cash = new com.an22.api.model.ShiftPayloads.Adjust(1L, 100.0, "found_cash", "");
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), cash, false)).isEqualTo("ปรับปรุงกะได้เฉพาะผู้ดูแลระบบ");
    assertThat(ShiftRules.shiftAdjustmentError("open", shift.counts(), cash, true)).isEqualTo("ปรับปรุงได้เฉพาะกะที่ปิดแล้ว");
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), new com.an22.api.model.ShiftPayloads.Adjust(9L, 100.0,
        "found_cash", ""), true)).isEqualTo("ปรับปรุงได้เฉพาะช่องทางที่นับตอนปิดกะ");
    assertThat(ShiftRules.shiftAdjustmentError("closed", List.of(count(null, 1000)), cash, true))
        .isEqualTo("ปรับปรุงได้เฉพาะช่องทางที่นับตอนปิดกะ");
    for (double amount : new double[] {0, 0.001}) {
      assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(),
          new com.an22.api.model.ShiftPayloads.Adjust(1L, amount, "found_cash", ""), true))
          .isEqualTo("จำนวนเงินต้องไม่เป็น 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)");
    }
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), new com.an22.api.model.ShiftPayloads.Adjust(1L, 100.0, "x", ""),
        true)).isEqualTo("กรุณาเลือกสาเหตุ");
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), new com.an22.api.model.ShiftPayloads.Adjust(1L, 100.0, "other",
        ""), true)).isEqualTo("กรุณาอธิบายสาเหตุ");
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), cash, true)).isNull();
    assertThat(ShiftRules.shiftAdjustmentError("closed", shift.counts(), new com.an22.api.model.ShiftPayloads.Adjust(1L, -50.0,
        "found_cash", ""), true)).isNull();

    List<AdjustmentRef> adjustments = List.of(new AdjustmentRef(1, 1, 100));
    assertThat(ShiftRules.adjustedVariance(shift.counts().get(0), adjustments)).isEqualTo(0);
    assertThat(ShiftRules.adjustedVariance(shift.counts().get(1), adjustments)).isEqualTo(0);
    assertThat(ShiftRules.varianceReport(List.of(shift), List.of(), List.of()).getFirst().shortTotal()).isEqualTo(100);
    assertThat(ShiftRules.varianceReport(List.of(shift), adjustments, List.of()).getFirst())
        .extracting(ShiftRules.VarianceReportRow::shortCount, ShiftRules.VarianceReportRow::shortTotal).containsExactly(0, 0.0);
  }
}
