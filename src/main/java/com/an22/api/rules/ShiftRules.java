package com.an22.api.rules;

import com.an22.api.model.DenominationCount;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.SalePayment;
import com.an22.api.model.ShiftCount;
import com.an22.api.model.ShiftPayloads;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * port จาก {@code core/models/shift.model.ts} — กฎเดียวของกะ (POS + server): ยอดระบบต่อช่องทาง, เปิด / ปิดกะ, นับแบบ blind (S2),
 * ส่วนต่าง + อนุมัติ (S3), เงินเข้าออก, เปิดลิ้นชัก (S7), ปรับปรุงหลังปิด (S8), รายงานส่วนต่าง.
 * <p>ต่างจาก TS อย่างเดียว: "เจ้าของกะ" เทียบด้วย id ผู้ใช้ ({@code cashierId} / {@code userId}) แทนชื่อ — เปลี่ยนชื่อผู้ใช้แล้วกะไม่หลุดมือ;
 * ชื่อยังใช้ในข้อความ / รายงานเหมือนเดิม.
 */
public final class ShiftRules {

  public static final Set<String> VARIANCE_REASONS = Set.of("wrong_change", "wrong_received", "wrong_method", "unknown", "other");
  public static final Set<String> ADJUSTMENT_REASONS = Set.of("count_error", "found_cash", "late_settlement", "other");
  /** แบงก์ / เหรียญไทย ใหญ่ก่อน. */
  public static final List<Double> THAI_DENOMINATIONS = List.of(1000.0, 500.0, 100.0, 50.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.25);

  private ShiftRules() {}

  /** กะเท่าที่กฎใช้: status, เจ้าของ (id + ชื่อ). */
  public record ShiftRef(String status, long cashierId, String cashier) {}

  /** การขายเท่าที่กฎกะใช้ ({@code ShiftSale}). */
  public record SaleRef(
      Long shiftId, Long voidShiftId, List<SalePayment> payments, double total, double itemDiscount, double billDiscount,
      double manualDiscount) {}

  /** ใบลดหนี้เท่าที่กฎกะใช้. */
  public record CreditNoteRef(Long shiftId, List<SalePayment> refunds, double total) {}

  public record MovementRef(long shiftId, String type, double amount) {}

  /** {@code ShiftContext}: exchanges / drawerOpens = shiftId ของแต่ละรายการ. */
  public record ShiftContext(
      List<SaleRef> sales, List<CreditNoteRef> creditNotes, List<MovementRef> movements, List<PaymentMethod> methods,
      List<Long> exchangeShiftIds, List<Long> drawerOpenShiftIds) {}

  /** {@code ExpectedLine}: ยอดระบบของช่องทางในกะ (เงินสดทุกช่องทางรวมเป็นบรรทัดลิ้นชักเดียว). */
  public record ExpectedLine(
      long methodId, String name, String type, String closeCheck, double opening, double sales, double refunds, double cashIn,
      double cashOut, double expected) {}

  /** {@code CloseChecklistItem}: สิ่งที่ต้องตรวจตอนปิด — ไม่มียอด (blind count). */
  public record CloseChecklistItem(long methodId, String name, String type, String closeCheck) {}

  public record CountCheckLine(long methodId, String name, String type, String closeCheck, double expected, Double counted,
      double variance) {}

  public record CountCheckResult(List<CountCheckLine> lines, boolean needsApproval) {}

  /** {@code ShiftSummary} (X / Z report) — lines เฉพาะ admin ระหว่างกะเปิด (S2): ไม่ส่ง key เมื่อไม่มี. */
  public record ShiftSummary(
      int billCount, double salesTotal, double discountTotal, int voidCount, double voidTotal, int refundCount, double refundTotal,
      int exchangeCount, int drawerOpenCount, double cashIn, double cashOut, double netSales, List<CloseChecklistItem> checklist,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ExpectedLine> lines) {}

  /** {@code VarianceReportRow}. */
  public record VarianceReportRow(
      String cashier, int shifts, int shortCount, int overCount, double shortTotal, double overTotal, int pending, int drawerOpens) {}

  /** กะที่ปิดแล้วสำหรับ {@link #varianceReport}. */
  public record ClosedShift(long id, String cashier, String status, String approvalStatus, List<ShiftCount> counts) {}

  public record AdjustmentRef(long shiftId, long methodId, double amount) {}

  // ---------- ตัวช่วย ----------

  /** ไม่ติดลบ ทศนิยมไม่เกิน 2. */
  static boolean isMoney(Double n) {
    return n != null && Double.isFinite(n) && n >= 0 && Money.round2(n) == n;
  }

  /** ทศนิยมไม่เกิน 2 ติดลบได้ (สุทธิหลังคืนเงิน, S9). */
  static boolean isSignedMoney(Double n) {
    return n != null && Double.isFinite(n) && Money.round2(n) == n;
  }

  /** {@code denominationTotal()}. */
  public static double denominationTotal(List<DenominationCount> counts) {
    double sum = 0;
    for (DenominationCount c : counts) {
      sum += c.value() * c.qty();
    }
    return Money.round2(sum);
  }

  static boolean denominationsValid(List<DenominationCount> counts) {
    return counts.stream().allMatch(c -> c.value() != null && THAI_DENOMINATIONS.contains(c.value()) && c.qty() != null
        && Double.isFinite(c.qty()) && c.qty() == Math.rint(c.qty()) && c.qty() >= 0);
  }

  /** ช่องทางเงินสดที่ใช้ชื่อบรรทัดลิ้นชัก: เงินสดที่ใช้งานตัวแรก (ตาม sortOrder). */
  private static PaymentMethod drawerMethod(List<PaymentMethod> methods) {
    return methods.stream()
        .filter(m -> "cash".equals(m.type()))
        .sorted(Comparator.comparing((PaymentMethod m) -> Boolean.TRUE.equals(m.active()) ? 0 : 1).thenComparing(PaymentMethod::sortOrder))
        .findFirst()
        .orElse(null);
  }

  /** บรรทัดระหว่างคิด. */
  private static final class Line {
    final long methodId;
    final String name;
    final String type;
    final String closeCheck;
    double opening;
    double sales;
    double refunds;
    double cashIn;
    double cashOut;

    Line(long methodId, String name, String type, String closeCheck) {
      this.methodId = methodId;
      this.name = name;
      this.type = type;
      this.closeCheck = closeCheck;
    }
  }

  /**
   * {@code expectedByMethod()}: ยอดที่รับในกะ − void / คืนเงินในกะ; เงินสดทุกช่องทางรวมบรรทัดลิ้นชัก (+ เงินทอนตั้งต้น + เงินเข้าออก);
   * ทุกช่องทางที่ใช้งาน + ช่องทางที่ปิดแล้วแต่มีเงินในกะ. ลำดับ: ลิ้นชักก่อน แล้วตาม sortOrder.
   */
  public static List<ExpectedLine> expectedByMethod(long shiftId, double openingFloat, ShiftContext ctx) {
    PaymentMethod drawer = drawerMethod(ctx.methods());
    long drawerKey = drawer == null ? 0 : drawer.id();
    Map<Long, Line> lines = new LinkedHashMap<>();
    LineFor lineFor = (methodId, name, type) -> {
      long key = "cash".equals(type) ? drawerKey : methodId;
      return lines.computeIfAbsent(key, k -> {
        PaymentMethod method = ctx.methods().stream().filter(m -> m.id() == key).findFirst().orElse(null);
        String lineType = method != null ? method.type() : type;
        return new Line(key, method != null ? method.name() : "cash".equals(type) ? "เงินสด" : name, lineType,
            PaymentMethodRules.closeCheckOf(lineType, method == null ? null : method.closeCheck()));
      });
    };

    lineFor.get(drawerKey, "เงินสด", "cash").opening = openingFloat;
    for (PaymentMethod m : ctx.methods()) {
      if (Boolean.TRUE.equals(m.active())) {
        lineFor.get(m.id(), m.name(), m.type());
      }
    }
    for (SaleRef sale : ctx.sales()) {
      for (SalePayment p : sale.payments()) {
        if (Objects.equals(sale.shiftId(), shiftId)) {
          lineFor.get(p.methodId(), p.name(), p.type()).sales += p.amount();
        }
        if (Objects.equals(sale.voidShiftId(), shiftId)) {
          lineFor.get(p.methodId(), p.name(), p.type()).refunds += p.amount();
        }
      }
    }
    for (CreditNoteRef note : ctx.creditNotes()) {
      if (!Objects.equals(note.shiftId(), shiftId)) {
        continue;
      }
      for (SalePayment p : note.refunds()) {
        lineFor.get(p.methodId(), p.name(), p.type()).refunds += p.amount();
      }
    }
    Line cash = lineFor.get(drawerKey, "เงินสด", "cash");
    for (MovementRef m : ctx.movements()) {
      if (m.shiftId() != shiftId) {
        continue;
      }
      if ("in".equals(m.type())) {
        cash.cashIn += m.amount();
      } else {
        cash.cashOut += m.amount();
      }
    }

    List<ExpectedLine> out = new ArrayList<>();
    for (Line l : lines.values()) {
      out.add(new ExpectedLine(l.methodId, l.name, l.type, l.closeCheck, l.opening, Money.round2(l.sales), Money.round2(l.refunds),
          Money.round2(l.cashIn), Money.round2(l.cashOut),
          Money.round2(l.opening + l.sales - l.refunds + l.cashIn - l.cashOut)));
    }
    out.sort(Comparator.comparingDouble(l -> l.methodId() == drawerKey ? Double.NEGATIVE_INFINITY
        : ctx.methods().stream().filter(m -> m.id() == l.methodId()).findFirst()
            .map(m -> (double) m.sortOrder()).orElse(Double.POSITIVE_INFINITY)));
    return out;
  }

  private interface LineFor {
    Line get(long methodId, String name, String type);
  }

  /** {@code drawerExpected()}: เงินสดที่ลิ้นชักควรมีตอนนี้. */
  public static double drawerExpected(List<ExpectedLine> lines) {
    return lines.stream().filter(l -> "cash".equals(l.type())).map(ExpectedLine::expected).findFirst().orElse(0.0);
  }

  /** {@code variance()}: นับได้ − ระบบ (+ เกิน / − ขาด); ไม่นับ = 0. */
  public static double variance(Double counted, double expected) {
    return counted == null ? 0 : Money.round2(counted - expected);
  }

  /** {@code needsApproval()}: ช่องทางใดช่องทางหนึ่งต่างเกินเพดาน (ไม่ใช่ผลรวม). */
  public static boolean needsApproval(List<ShiftCount> counts, double limit) {
    return counts.stream().anyMatch(c -> Math.abs(variance(c.counted(), c.expected())) > limit);
  }

  /** {@code approvalStatusFor()}. */
  public static String approvalStatusFor(List<ShiftCount> counts, double limit, String approvedBy) {
    return !needsApproval(counts, limit) ? "not_needed" : approvedBy != null ? "approved" : "pending";
  }

  /** {@code buildShiftCounts()}: ยอดที่เก็บตอนปิด (หลัง {@link #closeShiftError} ผ่าน). */
  public static List<ShiftCount> buildShiftCounts(List<ExpectedLine> expected, List<ShiftPayloads.CountInput> inputs, String user) {
    return expected.stream().map(line -> {
      ShiftPayloads.CountInput input = input(inputs, line.methodId());
      Double counted = "count".equals(line.closeCheck()) && input != null ? input.counted() : null;
      boolean off = counted != null && Money.round2(counted - line.expected()) != 0;
      return new ShiftCount(line.methodId(), line.name(), line.expected(), counted, line.closeCheck(),
          "confirm".equals(line.closeCheck()) && input != null && Boolean.TRUE.equals(input.confirmed()) ? user : null,
          counted != null && input != null ? input.firstCount() : null,
          off && input != null ? input.reasonCode() : null,
          off && input != null && input.reason() != null ? input.reason().strip() : "");
    }).toList();
  }

  /** {@code openShiftError()}. */
  public static String openShiftError(ShiftPayloads.Open p, boolean terminalHasOpenShift, boolean userHasOpenShift) {
    if (p.terminalId() == null || p.terminalId() <= 0) {
      return "ไม่ได้ระบุเครื่อง POS";
    }
    String floatProblem = Money.moneyInputError(p.openingFloat(), "เงินทอนตั้งต้น");
    if (floatProblem != null) {
      return floatProblem;
    }
    if (!denominationsValid(p.openingDenominations())) {
      return "จำนวนแบงก์/เหรียญไม่ถูกต้อง";
    }
    if (!p.openingDenominations().isEmpty() && denominationTotal(p.openingDenominations()) != p.openingFloat()) {
      return "ยอดรวมแบงก์/เหรียญไม่ตรงกับเงินทอนตั้งต้น";
    }
    if (terminalHasOpenShift) {
      return "เครื่องนี้มีกะที่เปิดอยู่แล้ว";
    }
    if (userHasOpenShift) {
      return "คุณมีกะที่เปิดอยู่ที่เครื่องอื่น — ปิดกะนั้นก่อน";
    }
    return null;
  }

  /**
   * {@code shiftBlocker()} (S1 / S6): ปิดระบบกะ = ทำได้เสมอ; ขายต้องเป็นกะเปิดของตัวเอง; void / คืนเงิน / เงินเข้าออก / เปิดลิ้นชักในกะของคนอื่น
   * เฉพาะ admin. action: sell / void / refund / cash_movement / drawer_open.
   */
  public static String shiftBlocker(String action, boolean requireShift, ShiftRef shift, long userId, boolean isAdmin) {
    if (!requireShift) {
      return null;
    }
    if (shift == null || !"open".equals(shift.status())) {
      return "ยังไม่ได้เปิดกะที่เครื่องนี้";
    }
    if (userId == shift.cashierId()) {
      return null;
    }
    if ("sell".equals(action)) {
      return "กะนี้เป็นของ " + shift.cashier() + " — ต้องปิดกะนั้นก่อนจึงจะขายได้";
    }
    if (!isAdmin) {
      return "กะนี้เป็นของ " + shift.cashier() + " — ทำรายการนี้ได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ";
    }
    return null;
  }

  /** {@code cashMovementError()}: บอกยอดที่ลิ้นชักควรมีเฉพาะ admin (blind count, B19). */
  public static String cashMovementError(ShiftPayloads.CashMove p, double expectedCash, boolean isAdmin) {
    if (!"in".equals(p.type()) && !"out".equals(p.type())) {
      return "ประเภทรายการไม่ถูกต้อง";
    }
    if (!isMoney(p.amount()) || p.amount() == 0) {
      return "จำนวนเงินต้องมากกว่า 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)";
    }
    String reason = p.reason() == null ? "" : p.reason().strip();
    if (reason.isEmpty()) {
      return "กรุณาระบุเหตุผล";
    }
    if (reason.length() > 200) {
      return "เหตุผลต้องไม่เกิน 200 ตัวอักษร";
    }
    if ("out".equals(p.type()) && p.amount() > expectedCash) {
      return isAdmin ? "เงินสดในลิ้นชักไม่พอ (คาดว่ามี " + Money.enNumber(expectedCash) + " บาท)" : "เงินสดในลิ้นชักไม่พอ";
    }
    return null;
  }

  /** {@code drawerOpenError()}. */
  public static String drawerOpenError(String reasonIn) {
    String reason = reasonIn == null ? "" : reasonIn.strip();
    if (reason.isEmpty()) {
      return "กรุณาระบุเหตุผลที่เปิดลิ้นชัก";
    }
    return reason.length() > 200 ? "เหตุผลต้องไม่เกิน 200 ตัวอักษร" : null;
  }

  /** {@code shiftAdjustmentError()} (S8). */
  public static String shiftAdjustmentError(String status, List<ShiftCount> counts, ShiftPayloads.Adjust p, boolean isAdmin) {
    if (!isAdmin) {
      return "ปรับปรุงกะได้เฉพาะผู้ดูแลระบบ";
    }
    if (!"closed".equals(status)) {
      return "ปรับปรุงได้เฉพาะกะที่ปิดแล้ว";
    }
    ShiftCount count = counts.stream().filter(c -> p.methodId() != null && c.methodId() == p.methodId()).findFirst().orElse(null);
    if (count == null || count.counted() == null) {
      return "ปรับปรุงได้เฉพาะช่องทางที่นับตอนปิดกะ";
    }
    Double amount = p.amount();
    if (amount == null || !Double.isFinite(amount) || amount == 0 || Money.round2(amount) != amount) {
      return "จำนวนเงินต้องไม่เป็น 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)";
    }
    if (p.reasonCode() == null || !ADJUSTMENT_REASONS.contains(p.reasonCode())) {
      return "กรุณาเลือกสาเหตุ";
    }
    String reason = p.reason() == null ? "" : p.reason().strip();
    if ("other".equals(p.reasonCode()) && reason.isEmpty()) {
      return "กรุณาอธิบายสาเหตุ";
    }
    return reason.length() > 200 ? "สาเหตุต้องไม่เกิน 200 ตัวอักษร" : null;
  }

  /** {@code adjustedVariance()}: ส่วนต่างของช่องทางหลังการปรับปรุงของกะ (S8). */
  public static double adjustedVariance(ShiftCount count, List<AdjustmentRef> adjustments) {
    double extra = 0;
    for (AdjustmentRef a : adjustments) {
      if (a.methodId() == count.methodId()) {
        extra += a.amount();
      }
    }
    return Money.round2(variance(count.counted(), count.expected()) + extra);
  }

  /** {@code closeChecklist()}. */
  public static List<CloseChecklistItem> closeChecklist(List<ExpectedLine> expected) {
    return expected.stream().map(l -> new CloseChecklistItem(l.methodId(), l.name(), l.type(), l.closeCheck())).toList();
  }

  /** {@code countInputsError()}: count = มียอด (เงินสด ≥ 0, อื่นติดลบได้), confirm = ติ๊กแล้ว. */
  public static String countInputsError(List<ExpectedLine> expected, List<ShiftPayloads.CountInput> inputs) {
    for (ExpectedLine line : expected) {
      ShiftPayloads.CountInput input = input(inputs, line.methodId());
      if ("none".equals(line.closeCheck())) {
        continue;
      }
      if ("confirm".equals(line.closeCheck())) {
        if (input == null || !Boolean.TRUE.equals(input.confirmed())) {
          return "กรุณาตรวจยอด " + line.name() + " แล้วติ๊ก \"ตรวจแล้ว\"";
        }
        continue;
      }
      boolean cash = "cash".equals(line.type());
      if (input == null || !(cash ? isMoney(input.counted()) : isSignedMoney(input.counted()))) {
        return "กรุณากรอกยอดที่นับได้ของ " + line.name();
      }
      if (input.firstCount() != null && !(cash ? isMoney(input.firstCount()) : isSignedMoney(input.firstCount()))) {
        return "ยอดนับครั้งแรกของ " + line.name() + " ไม่ถูกต้อง";
      }
    }
    return null;
  }

  /** {@code countCheck()}: เปิดยอดระบบ / ส่วนต่างหลังนับแบบ blind ครบแล้ว. */
  public static CountCheckResult countCheck(List<ExpectedLine> expected, List<ShiftPayloads.CountInput> inputs, double limit) {
    List<CountCheckLine> lines = expected.stream().map(line -> {
      ShiftPayloads.CountInput input = input(inputs, line.methodId());
      Double counted = "count".equals(line.closeCheck()) && input != null ? input.counted() : null;
      return new CountCheckLine(line.methodId(), line.name(), line.type(), line.closeCheck(), line.expected(), counted,
          variance(counted, line.expected()));
    }).toList();
    boolean approval = lines.stream().anyMatch(l -> Math.abs(l.variance()) > limit);
    return new CountCheckResult(lines, approval);
  }

  /**
   * {@code closeShiftError()}: กะเปิด, ปิดโดยเจ้าของกะหรือ admin, ตรวจครบทุกช่องทาง, ส่วนต่างมีสาเหตุ, เงินนำส่งไม่เกินเงินสดที่นับ —
   * ส่วนต่างเกินเพดานไม่ใช่ error (ปิดเป็น pending).
   */
  public static String closeShiftError(ShiftRef shift, ShiftPayloads.Close p, List<ExpectedLine> expected, long userId, boolean isAdmin) {
    if (!"open".equals(shift.status())) {
      return "กะนี้ปิดไปแล้ว";
    }
    if (userId != shift.cashierId() && !isAdmin) {
      return "ปิดกะได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ";
    }
    String countProblem = countInputsError(expected, p.counts());
    if (countProblem != null) {
      return countProblem;
    }
    for (ExpectedLine line : expected) {
      if (!"count".equals(line.closeCheck())) {
        continue;
      }
      ShiftPayloads.CountInput input = input(p.counts(), line.methodId());
      double diff = Money.round2((input.counted() == null ? 0 : input.counted()) - line.expected());
      if (diff == 0) {
        continue;
      }
      if (input.reasonCode() == null || !VARIANCE_REASONS.contains(input.reasonCode())) {
        return line.name() + " " + (diff > 0 ? "เกิน" : "ขาด") + " " + Money.enNumber(Math.abs(diff)) + " บาท — กรุณาระบุสาเหตุ";
      }
      String reason = input.reason() == null ? "" : input.reason().strip();
      if ("other".equals(input.reasonCode()) && reason.isEmpty()) {
        return "กรุณาอธิบายสาเหตุส่วนต่างของ " + line.name();
      }
      if (reason.length() > 200) {
        return "สาเหตุต้องไม่เกิน 200 ตัวอักษร";
      }
    }
    ExpectedLine drawer = expected.stream().filter(l -> "cash".equals(l.type())).findFirst().orElse(null);
    ShiftPayloads.CountInput drawerInput = drawer == null ? null : input(p.counts(), drawer.methodId());
    double cashCounted = drawerInput != null && drawerInput.counted() != null ? drawerInput.counted() : 0;
    if (!denominationsValid(p.cashDenominations())) {
      return "จำนวนแบงก์/เหรียญไม่ถูกต้อง";
    }
    if (!p.cashDenominations().isEmpty() && denominationTotal(p.cashDenominations()) != cashCounted) {
      return "ยอดรวมแบงก์/เหรียญไม่ตรงกับยอดเงินสดที่นับ";
    }
    if (!isMoney(p.depositAmount()) || p.depositAmount() > cashCounted) {
      return "เงินนำส่งต้องอยู่ระหว่าง 0 ถึงยอดเงินสดที่นับได้";
    }
    return p.note() != null && p.note().length() > 500 ? "หมายเหตุต้องไม่เกิน 500 ตัวอักษร" : null;
  }

  /** {@code shiftSummary()}: X report (กะเปิด) / Z report (ปิดแล้ว); detailed false = ไม่มียอดต่อช่องทาง. */
  public static ShiftSummary shiftSummary(long shiftId, double openingFloat, ShiftContext ctx, boolean detailed) {
    List<SaleRef> sold = ctx.sales().stream().filter(s -> Objects.equals(s.shiftId(), shiftId)).toList();
    List<SaleRef> voided = ctx.sales().stream().filter(s -> Objects.equals(s.voidShiftId(), shiftId)).toList();
    List<CreditNoteRef> notes = ctx.creditNotes().stream().filter(n -> Objects.equals(n.shiftId(), shiftId)).toList();
    List<MovementRef> moves = ctx.movements().stream().filter(m -> m.shiftId() == shiftId).toList();
    double salesTotal = sum(sold.stream().map(SaleRef::total).toList());
    double voidTotal = sum(voided.stream().map(SaleRef::total).toList());
    double refundTotal = sum(notes.stream().map(CreditNoteRef::total).toList());
    List<ExpectedLine> expected = expectedByMethod(shiftId, openingFloat, ctx);
    return new ShiftSummary(
        sold.size(), salesTotal, sum(sold.stream().map(s -> s.itemDiscount() + s.billDiscount() + s.manualDiscount()).toList()),
        voided.size(), voidTotal, notes.size(), refundTotal,
        (int) ctx.exchangeShiftIds().stream().filter(id -> id == shiftId).count(),
        (int) ctx.drawerOpenShiftIds().stream().filter(id -> id == shiftId).count(),
        sum(moves.stream().filter(m -> "in".equals(m.type())).map(MovementRef::amount).toList()),
        sum(moves.stream().filter(m -> "out".equals(m.type())).map(MovementRef::amount).toList()),
        Money.round2(salesTotal - voidTotal - refundTotal), closeChecklist(expected), detailed ? expected : null);
  }

  /**
   * {@code varianceReport()}: กะที่ปิดแล้วรายแคชเชียร์ (ส่วนต่างหลังปรับปรุง S8, เปิดลิ้นชัก S7) ขาดมากสุดก่อน แล้วตามชื่อ.
   */
  public static List<VarianceReportRow> varianceReport(List<ClosedShift> shifts, List<AdjustmentRef> adjustments,
      List<Long> drawerOpenShiftIds) {
    Map<String, VarianceReportRow> rows = new LinkedHashMap<>();
    for (ClosedShift shift : shifts) {
      if (!"closed".equals(shift.status())) {
        continue;
      }
      VarianceReportRow row = rows.getOrDefault(shift.cashier(), new VarianceReportRow(shift.cashier(), 0, 0, 0, 0, 0, 0, 0));
      List<AdjustmentRef> own = adjustments.stream().filter(a -> a.shiftId() == shift.id()).toList();
      List<Double> diffs = shift.counts().stream().map(c -> adjustedVariance(c, own)).toList();
      int opens = (int) drawerOpenShiftIds.stream().filter(id -> id == shift.id()).count();
      double shortSum = 0;
      double overSum = 0;
      for (double d : diffs) {
        if (d < 0) {
          shortSum -= d;
        } else if (d > 0) {
          overSum += d;
        }
      }
      double isShort = Money.round2(shortSum);
      double over = Money.round2(overSum);
      rows.put(shift.cashier(), new VarianceReportRow(shift.cashier(), row.shifts() + 1, row.shortCount() + (isShort > 0 ? 1 : 0),
          row.overCount() + (over > 0 ? 1 : 0), Money.round2(row.shortTotal() + isShort), Money.round2(row.overTotal() + over),
          row.pending() + ("pending".equals(shift.approvalStatus()) ? 1 : 0), row.drawerOpens() + opens));
    }
    Collator thai = Collator.getInstance(Locale.forLanguageTag("th"));
    return rows.values().stream()
        .sorted(Comparator.comparingDouble(VarianceReportRow::shortTotal).reversed()
            .thenComparing(VarianceReportRow::cashier, thai))
        .toList();
  }

  /** {@code requireShiftChangeError()}: ปิดระบบกะได้เมื่อไม่มีกะเปิด. */
  public static String requireShiftChangeError(boolean current, boolean next, int openShiftCount) {
    if (current && !next && openShiftCount > 0) {
      return "ยังมีกะที่เปิดอยู่ " + openShiftCount + " กะ — ปิดกะทั้งหมดก่อนปิดการใช้ระบบกะ";
    }
    return null;
  }

  private static ShiftPayloads.CountInput input(List<ShiftPayloads.CountInput> inputs, long methodId) {
    return inputs.stream().filter(i -> i.methodId() != null && i.methodId() == methodId).findFirst().orElse(null);
  }

  /** ผลรวมปัดสตางค์ ({@code sum()} ของ shiftSummary: บวกจากซ้ายแล้วปัดครั้งเดียว). */
  private static double sum(List<Double> values) {
    double total = 0;
    for (double v : values) {
      total += v;
    }
    return Money.round2(total);
  }
}
