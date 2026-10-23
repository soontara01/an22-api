package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.CreditNote;
import com.an22.api.model.Exchange;
import com.an22.api.model.PaymentInput;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.Promotion;
import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import com.an22.api.model.SalePayment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * port จาก {@code core/models/credit-note.model.ts} — ใบลดหนี้ (ตกลง 2026-10-04): คืนส่วนที่ลูกค้าจ่ายจริงของบรรทัด, หักของแถมที่เก็บไว้เกินสิทธิ์,
 * คืนเงินเป็นเงินสดหรือช่องทางที่บิลจ่ายมา. ผลต้องตรง {@code docs/backend/fixtures/credit-note.json} (FixturesTest).
 */
public final class CreditNoteRules {

  private CreditNoteRules() {}

  /** {@code RefundOption}: max null = ไม่จำกัดนอกจากยอดรวม (เงินสด). */
  public record RefundOption(PaymentMethod method, Double max) {}

  /** {@code returnSummary()} — field ที่ server คำนวณของ Sale. */
  public record ReturnSummary(String returnStatus, double creditedTotal, List<String> creditNoteNos) {}

  private static List<CreditNote.Line> creditedLines(List<CreditNote> notes, int index) {
    return notes.stream().flatMap(n -> n.lines().stream()).filter(l -> l.saleLineIndex() == index).toList();
  }

  /** {@code creditedQty()}: หน่วยของบรรทัดบิลที่คืนไปแล้ว. */
  public static int creditedQty(List<CreditNote> notes, int index) {
    return creditedLines(notes, index).stream().mapToInt(CreditNote.Line::qty).sum();
  }

  /** {@code returnSummary()}: full เมื่อทุกหน่วยของทุกบรรทัดคืนแล้ว, partial เมื่อคืนบางส่วน. */
  public static ReturnSummary returnSummary(List<SaleLine> lines, List<CreditNote> notes) {
    if (notes.isEmpty()) {
      return new ReturnSummary("none", 0, List.of());
    }
    boolean full = true;
    for (int i = 0; i < lines.size(); i++) {
      if (creditedQty(notes, i) < lines.get(i).qty()) {
        full = false;
      }
    }
    double total = 0;
    for (CreditNote n : notes) {
      total += n.total();
    }
    return new ReturnSummary(full ? "full" : "partial", Money.round2(total),
        notes.stream().sorted(Comparator.comparing(CreditNote::date)).map(CreditNote::cnNo).toList());
  }

  /** {@code remainingQty()}: หน่วยของบรรทัดที่ยังคืนได้. */
  public static int remainingQty(Sale sale, List<CreditNote> notes, int index) {
    int qty = index >= 0 && index < sale.lines().size() ? sale.lines().get(index).qty() : 0;
    return qty - creditedQty(notes, index);
  }

  private record Share(double amount, double vat, double cogs) {}

  /** {@code lineShare()}: หน่วยสุดท้ายได้เศษที่เหลือพอดี; ไม่จด VAT ตอนขาย = VAT 0 (B20). */
  private static Share lineShare(SaleLine line, List<CreditNote> notes, int index, int qty, boolean withVat) {
    List<CreditNote.Line> before = creditedLines(notes, index);
    if (creditedQty(notes, index) + qty == line.qty()) {
      return new Share(Money.round2(line.amount() - sum(before.stream().map(CreditNote.Line::amount).toList())),
          Money.round2(line.vat() - sum(before.stream().map(CreditNote.Line::vat).toList())),
          Money.round2(line.cogs() - sum(before.stream().map(CreditNote.Line::cogs).toList())));
    }
    double amount = Money.round2((line.amount() * qty) / line.qty());
    return new Share(amount, withVat ? ProductRules.vatBreakdown(amount, line.vatType())[1] : 0,
        Money.round2((line.cogs() * qty) / line.qty()));
  }

  private static CreditNote.Draft fail(String error) {
    return new CreditNote.Draft(List.of(), List.of(), 0, 0, 0, 0, error);
  }

  /** {@code draftCreditNote()}: คิดยอดคืนของบรรทัดที่เลือก (ไม่ตรวจการคืนเงิน / เหตุผล). */
  public static CreditNote.Draft draftCreditNote(Sale sale, List<CreditNote> notes, List<CreditNote.LineInput> inputs,
      List<Promotion> promotions, List<Exchange> exchanges) {
    List<CreditNote.LineInput> chosen = inputs.stream().filter(i -> i.qty() != null && i.qty() > 0).toList();
    if (chosen.isEmpty()) {
      return fail("กรุณาเลือกสินค้าที่รับคืน");
    }
    if (new HashSet<>(chosen.stream().map(CreditNote.LineInput::saleLineIndex).toList()).size() != chosen.size()) {
      return fail("เลือกรายการซ้ำ");
    }
    boolean withVat = sale.vatRegistered();
    List<CreditNote.Line> lines = new ArrayList<>();
    for (CreditNote.LineInput input : chosen) {
      int index = input.saleLineIndex() == null ? -1 : input.saleLineIndex();
      SaleLine line = index >= 0 && index < sale.lines().size() ? sale.lines().get(index) : null;
      if (line == null) {
        return fail("ไม่พบรายการที่ " + (index + 1) + " ในบิล");
      }
      int left = remainingQty(sale, notes, index);
      double qty = input.qty();
      if (qty != Math.rint(qty) || qty > left) {
        return fail(line.sku() + " คืนได้ไม่เกิน " + left + " " + line.unit());
      }
      Share share = lineShare(line, notes, index, (int) qty, withVat);
      lines.add(new CreditNote.Line(index, line.productId(), line.sku(), line.name(), line.shortName(), line.unit(), line.factor(),
          (int) qty, share.amount(), line.vatType(), share.vat(), share.cogs(), SaleRules.currentSerial(sale.lines(), index, exchanges),
          line.freeOfPromotionId() != null, !"service".equals(line.itemType()) && Boolean.TRUE.equals(input.restock())));
    }

    // ของแถมที่เก็บไว้เกินสิ่งที่ของที่เหลือยังได้สิทธิ์
    List<CreditNote.Deduction> deductions = new ArrayList<>();
    Set<Long> promoIds = new LinkedHashSet<>();
    for (SaleLine l : sale.lines()) {
      if (l.freeOfPromotionId() != null) {
        promoIds.add(l.freeOfPromotionId());
      }
    }
    for (long promotionId : promoIds) {
      Promotion promo = promotions.stream().filter(p -> p.id() != null && p.id() == promotionId).findFirst().orElse(null);
      if (promo == null || promo.freeGoods() == null) {
        continue;
      }
      double qty = 0;
      double amount = 0;
      for (int i = 0; i < sale.lines().size(); i++) {
        SaleLine l = sale.lines().get(i);
        if (l.freeOfPromotionId() != null || !l.promotionIds().contains(promotionId)) {
          continue;
        }
        int kept = keptQty(sale, notes, lines, i);
        qty += (double) kept * l.factor();
        amount += ((l.unitPrice() * l.qty() - l.itemDiscount()) * kept) / l.qty();
      }
      int sets = PosPricing.freeSets(promo, qty, Money.round2(amount));
      List<Integer> freeIndexes = new ArrayList<>();
      for (int i = 0; i < sale.lines().size(); i++) {
        if (Objects.equals(sale.lines().get(i).freeOfPromotionId(), promotionId)) {
          freeIndexes.add(i);
        }
      }
      Set<Long> products = new LinkedHashSet<>();
      freeIndexes.forEach(i -> products.add(sale.lines().get(i).productId()));
      for (long productId : products) {
        List<Integer> own = freeIndexes.stream().filter(i -> sale.lines().get(i).productId() == productId).toList();
        int given = 0;
        int kept = 0;
        for (int i : own) {
          SaleLine l = sale.lines().get(i);
          given += l.qty() * l.factor();
          kept += keptQty(sale, notes, lines, i) * l.factor();
        }
        double perSet = promo.freeGoods().items().stream().filter(x -> Objects.equals(x.productId(), productId))
            .map(Promotion.FreeItem::qty).findFirst().orElse(0.0);
        double allowed = Math.min(given, sets * perSet);
        int charged = notes.stream().flatMap(n -> n.deductions().stream())
            .filter(d -> d.promotionId() == promotionId && d.productId() == productId).mapToInt(CreditNote.Deduction::qty).sum();
        double excess = Math.max(0, kept - allowed - charged);
        if (excess == 0) {
          continue;
        }
        SaleLine sample = sale.lines().get(own.getFirst());
        double unitPrice = Money.round2(sample.listPrice() / sample.factor());
        deductions.add(new CreditNote.Deduction(promotionId, productId, sample.sku(), sample.name(), (int) excess, unitPrice,
            Money.round2(excess * unitPrice), sample.vatType()));
      }
    }

    double subtotal = sum(lines.stream().map(CreditNote.Line::amount).toList());
    double deduction = sum(deductions.stream().map(CreditNote.Deduction::amount).toList());
    double total = Money.round2(subtotal - deduction);
    double vat = withVat
        ? Money.round2(sum(lines.stream().map(CreditNote.Line::vat).toList())
            - sum(deductions.stream().map(d -> ProductRules.vatBreakdown(d.amount(), d.vatType())[1]).toList()))
        : 0;
    return new CreditNote.Draft(List.copyOf(lines), List.copyOf(deductions), subtotal, deduction, total, vat,
        total < 0 ? "มูลค่าของแถมที่ต้องหักเกินยอดคืน กรุณารับของแถมคืนด้วย" : null);
  }

  /** หน่วยที่ลูกค้ายังเก็บไว้หลังใบนี้. */
  private static int keptQty(Sale sale, List<CreditNote> notes, List<CreditNote.Line> returning, int index) {
    int now = returning.stream().filter(l -> l.saleLineIndex() == index).mapToInt(CreditNote.Line::qty).findFirst().orElse(0);
    return sale.lines().get(index).qty() - creditedQty(notes, index) - now;
  }

  /** {@code refundOptions()}: เงินสดที่ใช้งาน (ไม่จำกัด) + ช่องทางที่บิลจ่ายมา ไม่เกินที่รับมาลบที่คืนไปแล้ว. */
  public static List<RefundOption> refundOptions(Sale sale, List<CreditNote> notes, List<PaymentMethod> methods) {
    List<RefundOption> options = new ArrayList<>();
    methods.stream().filter(m -> Boolean.TRUE.equals(m.active())).sorted(Comparator.comparing(PaymentMethod::sortOrder)).forEach(m -> {
      if (PaymentMethodRules.allowsChange(m.type())) {
        options.add(new RefundOption(m, null));
        return;
      }
      double paid = sum(sale.payments().stream().filter(p -> p.methodId() == m.id()).map(SalePayment::amount).toList());
      if (paid == 0) {
        return;
      }
      double refunded = sum(notes.stream().flatMap(n -> n.refunds().stream()).filter(r -> r.methodId() == m.id())
          .map(SalePayment::amount).toList());
      options.add(new RefundOption(m, Money.round2(paid - refunded)));
    });
    return options.stream().filter(o -> o.max() == null || o.max() > 0).toList();
  }

  /**
   * {@code creditNoteError()}: บิลที่ชำระแล้ว ที่สาขาที่ขาย (D3) ไม่ใช่วันนี้, ร่างใช้ได้, มีเหตุผล, ยอดตรงหน้าจอ, การคืนเงินถูกช่องทาง / ไม่เกิน
   * / มีเลขอ้างอิง / ไม่ใช่เลขบัตรเต็ม และรวมเท่ายอดลดหนี้.
   */
  public static String creditNoteError(Sale sale, List<CreditNote> notes, CreditNote.Payload payload, List<Promotion> promotions,
      List<PaymentMethod> methods, String today, List<Exchange> exchanges, Long hereBranchId, List<Branch> branches) {
    if (!"paid".equals(sale.status()) || sale.lines().isEmpty()) {
      return "ออกใบลดหนี้ได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว";
    }
    String elsewhere = SaleRules.branchMismatchError(sale.branchId(), hereBranchId, branches, "ออกใบลดหนี้");
    if (elsewhere != null) {
      return elsewhere;
    }
    if (SaleRules.saleDay(sale.date()).equals(today)) {
      return "บิลของวันนี้ให้ยกเลิกบิลแทนการออกใบลดหนี้";
    }
    CreditNote.Draft draft = draftCreditNote(sale, notes, payload.lines(), promotions, exchanges);
    if (draft.error() != null) {
      return draft.error();
    }
    if (payload.reason() == null || payload.reason().isBlank()) {
      return "กรุณาระบุเหตุผลการลดหนี้";
    }
    if (payload.expectedTotal() == null || Money.round2(payload.expectedTotal()) != draft.total()) {
      return "ยอดลดหนี้เปลี่ยน กรุณาตรวจสอบอีกครั้ง";
    }
    List<RefundOption> options = refundOptions(sale, notes, methods);
    Set<Long> seen = new HashSet<>();
    for (PaymentInput refund : payload.refunds()) {
      RefundOption option = options.stream().filter(o -> Objects.equals(o.method().id(), refund.methodId())).findFirst().orElse(null);
      if (option == null) {
        return "คืนเงินได้เฉพาะเงินสดหรือช่องทางที่ลูกค้าชำระบิลนี้";
      }
      PaymentMethod method = option.method();
      if (!seen.add(method.id())) {
        return method.name() + " ซ้ำ";
      }
      double amount = refund.amount() == null ? Double.NaN : refund.amount();
      if (!(Double.isFinite(amount) && amount > 0 && Money.round2(amount) == amount)) {
        return method.name() + ": จำนวนเงินไม่ถูกต้อง";
      }
      if (PosPayment.isFullCardNumber(refund.reference())) {
        return method.name() + ": ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย";
      }
      if (option.max() != null && amount > option.max()) {
        return method.name() + ": คืนได้ไม่เกิน " + Money.enNumber(option.max()) + " บาท";
      }
      if (!PaymentMethodRules.allowsChange(method.type()) && Boolean.TRUE.equals(method.requireReference())
          && (refund.reference() == null || refund.reference().isBlank())) {
        String label = method.referenceLabel() == null || method.referenceLabel().isEmpty() ? "เลขอ้างอิง" : method.referenceLabel();
        return method.name() + ": กรุณากรอก" + label;
      }
    }
    double refunded = sum(payload.refunds().stream().map(r -> r.amount() == null ? Double.NaN : r.amount()).toList());
    if (refunded != draft.total()) {
      return "ยอดคืนเงิน " + Money.enNumber(refunded) + " ไม่เท่ายอดลดหนี้ " + Money.enNumber(draft.total());
    }
    return null;
  }

  /** {@code toRefunds()}: บรรทัดคืนเงินที่เก็บ (snapshot ช่องทาง). */
  public static List<SalePayment> toRefunds(List<PaymentInput> inputs, List<PaymentMethod> methods) {
    List<SalePayment> out = new ArrayList<>();
    for (PaymentInput input : inputs) {
      methods.stream().filter(m -> Objects.equals(m.id(), input.methodId())).findFirst().ifPresent(m -> out.add(new SalePayment(m.id(),
          m.name(), m.type(), input.amount(), input.amount(), input.reference() == null ? "" : input.reference().strip(), null)));
    }
    return out;
  }

  private static double sum(List<Double> values) {
    double total = 0;
    for (double v : values) {
      total += v;
    }
    return Money.round2(total);
  }
}
