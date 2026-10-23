package com.an22.api.rules;

import com.an22.api.model.PaymentInput;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.SalePayment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * port จาก {@code core/models/pos-payment.model.ts} — กฎชำระเงินของบิล (POS + server): แบ่งหลายช่องทางได้ เงินสดบรรทัดเดียว,
 * ไม่ใช่เงินสดตัดตรงยอดและรวมกันไม่เกินยอดบิล, เงินสดปัดตาม {@code cashRounding} แล้วทอนส่วนเกิน. ผลต้องตรง
 * {@code docs/backend/fixtures/payment.json} (FixturesTest).
 */
public final class PosPayment {

  private PosPayment() {}

  /** {@code PaymentSummary}. */
  public record PaymentSummary(
      List<SalePayment> payments,
      double nonCash,
      double remaining,
      double cashDue,
      double rounding,
      double tendered,
      double change,
      double balance,
      boolean complete,
      boolean opensDrawer) {}

  /** {@code cashDueFor()}: ยอดที่ต้องรับเป็นเงินสด (ปัดตามช่องทางเงินสด). */
  public static double cashDueFor(double remaining, String cashRounding) {
    return PaymentMethodRules.roundCash(Math.max(remaining, 0), cashRounding);
  }

  private record Resolved(PaymentInput input, PaymentMethod method) {}

  public static PaymentSummary paymentSummary(double total, List<PaymentInput> inputs, List<PaymentMethod> methods) {
    Map<Long, PaymentMethod> byId = byId(methods);
    List<Resolved> resolved = new ArrayList<>();
    for (PaymentInput input : inputs) {
      PaymentMethod method = byId.get(input.methodId());
      if (method != null) {
        resolved.add(new Resolved(input, method));
      }
    }
    double nonCashSum = 0;
    Resolved cashLine = null;
    for (Resolved x : resolved) {
      if (PaymentMethodRules.allowsChange(x.method().type())) {
        if (cashLine == null) {
          cashLine = x;
        }
      } else {
        nonCashSum += amount(x.input());
      }
    }
    double nonCash = Money.round2(nonCashSum);
    double remaining = Money.round2(Math.max(total - nonCash, 0));
    double cashDue = cashLine != null ? cashDueFor(remaining, cashLine.method().cashRounding()) : 0;
    double tendered = cashLine != null ? Money.round2(amount(cashLine.input())) : 0;
    boolean settled = cashLine != null ? tendered >= cashDue : remaining == 0;
    double balance = settled ? 0 : Money.round2(cashLine != null ? cashDue - tendered : remaining);

    List<SalePayment> payments = new ArrayList<>();
    for (Resolved x : resolved) {
      PaymentMethod method = x.method();
      boolean cash = PaymentMethodRules.allowsChange(method.type());
      double paid = cash ? Math.min(cashDue, tendered) : Money.round2(amount(x.input()));
      payments.add(new SalePayment(method.id(), method.name(), method.type(), paid, cash ? tendered : paid,
          x.input().reference() == null ? "" : x.input().reference().strip(),
          "installment".equals(method.type()) ? x.input().installmentMonths() : null));
    }
    return new PaymentSummary(
        List.copyOf(payments),
        nonCash,
        remaining,
        cashDue,
        cashLine != null && settled ? Money.round2(cashDue - remaining) : 0,
        tendered,
        settled && cashLine != null ? Money.round2(tendered - cashDue) : 0,
        balance,
        settled,
        resolved.stream().anyMatch(x -> PaymentMethodRules.opensDrawer(x.method().type())));
  }

  /** {@code paymentError()}: กฎเดียวของการชำระ — null = ชำระครบ. */
  public static String paymentError(double total, List<PaymentInput> inputs, List<PaymentMethod> methods) {
    if (total == 0) {
      return inputs.isEmpty() ? null : "บิลยอด 0 บาทไม่ต้องรับชำระ";
    }
    if (inputs.isEmpty()) {
      return "กรุณาเลือกช่องทางชำระเงิน";
    }
    Map<Long, PaymentMethod> byId = byId(methods);
    int cashLines = 0;
    for (PaymentInput input : inputs) {
      PaymentMethod method = byId.get(input.methodId());
      if (method == null) {
        return "ไม่พบช่องทางชำระเงิน #" + input.methodId();
      }
      if (!Boolean.TRUE.equals(method.active())) {
        return method.name() + " ปิดใช้งาน";
      }
      double amount = amount(input);
      if (!isMoney(amount)) {
        return method.name() + ": จำนวนเงินไม่ถูกต้อง";
      }
      if (PaymentMethodRules.allowsChange(method.type())) {
        cashLines++;
        continue;
      }
      if (amount <= 0) {
        return method.name() + ": จำนวนเงินต้องมากกว่า 0";
      }
      if (Boolean.TRUE.equals(method.requireReference()) && (input.reference() == null || input.reference().isBlank())) {
        String label = method.referenceLabel() == null || method.referenceLabel().isEmpty() ? "เลขอ้างอิง" : method.referenceLabel();
        return method.name() + ": กรุณากรอก" + label;
      }
      if ("installment".equals(method.type())
          && !method.installmentMonths().contains(input.installmentMonths() == null ? -1 : input.installmentMonths())) {
        return method.name() + ": กรุณาเลือกจำนวนเดือนผ่อน";
      }
    }
    if (cashLines > 1) {
      return "รับเงินสดได้บรรทัดเดียว";
    }

    PaymentSummary summary = paymentSummary(total, inputs, methods);
    if (summary.nonCash() > total) {
      return "ยอดชำระที่ไม่ใช่เงินสดเกินยอดบิล (ทอนได้เฉพาะเงินสด)";
    }
    if (!summary.complete()) {
      return "ยอดชำระยังไม่ครบ (ขาด " + baht(summary.balance()) + ")";
    }
    for (SalePayment p : summary.payments()) {
      PaymentMethod method = byId.get(p.methodId());
      if (PaymentMethodRules.allowsChange(method.type()) && summary.remaining() == 0) {
        return "ไม่มียอดที่ต้องรับเป็นเงินสด";
      }
      if (p.amount() < method.minAmount()) {
        return method.name() + ": ขั้นต่ำ " + baht(method.minAmount()) + " ต่อรายการ";
      }
      if (method.maxAmount() != null && p.amount() > method.maxAmount()) {
        return method.name() + ": สูงสุด " + baht(method.maxAmount()) + " ต่อรายการ";
      }
    }
    return null;
  }

  /**
   * {@code ฿} + {@code toLocaleString('en-US', { maximumFractionDigits: 2 })}: คั่นหลักพัน, ทศนิยมไม่เกิน 2 ไม่เติม 0, ปัดครึ่งออกจากศูนย์
   * (halfExpand ของ Intl) บนค่าทศนิยมที่สั้นที่สุดของ double (เหมือน JS).
   */
  public static String baht(double n) {
    DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
    format.setRoundingMode(RoundingMode.HALF_UP);
    return "฿" + format.format(BigDecimal.valueOf(n));
  }

  private static double amount(PaymentInput input) {
    return input.amount() == null ? Double.NaN : input.amount();
  }

  private static boolean isMoney(double n) {
    return Double.isFinite(n) && n >= 0 && Money.round2(n) == n;
  }

  private static Map<Long, PaymentMethod> byId(List<PaymentMethod> methods) {
    Map<Long, PaymentMethod> byId = new HashMap<>();
    for (PaymentMethod m : methods) {
      byId.put(m.id(), m);
    }
    return byId;
  }
}
