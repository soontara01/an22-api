package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.PosTerminal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code payment-method.model.spec.ts}, {@code pos-terminal.model.spec.ts}, {@code money.model.spec.ts}. */
class MasterDataRulesTest {

  /** {@code method(id, extra)} ของ spec: ค่าเริ่ม + code M-id, ชื่อ Mid, ประเภท card. */
  private static PaymentMethod method(long id, String type, String code) {
    return new PaymentMethod(id, code, "M" + id, type, true, 0, false, "", 0.0, null, 0.0, "none", "", "", List.of(), null, "");
  }

  private static PaymentMethod with(PaymentMethod m, String field, Object value) {
    return new PaymentMethod(m.id(), field.equals("code") ? (String) value : m.code(), field.equals("name") ? (String) value : m.name(),
        field.equals("type") ? (String) value : m.type(), field.equals("active") ? (Boolean) value : m.active(), m.sortOrder(),
        field.equals("requireReference") ? (Boolean) value : m.requireReference(),
        field.equals("referenceLabel") ? (String) value : m.referenceLabel(), field.equals("minAmount") ? (Double) value : m.minAmount(),
        field.equals("maxAmount") ? (Double) value : m.maxAmount(), field.equals("feePercent") ? (Double) value : m.feePercent(),
        field.equals("cashRounding") ? (String) value : m.cashRounding(), field.equals("promptPayId") ? (String) value : m.promptPayId(),
        m.bankAccount(), field.equals("installmentMonths") ? castList(value) : m.installmentMonths(), m.closeCheck(), m.note());
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> castList(Object value) {
    return (List<Integer>) value;
  }

  private static final PaymentMethod CASH = method(1, "cash", "CASH");
  private static final List<PaymentMethod> ALL = List.of(CASH, method(2, "card", "M-2"));

  private static String check(PaymentMethod m, Long exceptId) {
    return PaymentMethodRules.paymentMethodError(PaymentMethodRules.normalize(m), ALL, exceptId);
  }

  @Test
  void knowsCashBehaviourRoundingAndNetReceived() {
    assertThat(PaymentMethodRules.allowsChange("cash") && PaymentMethodRules.opensDrawer("cash")).isTrue();
    assertThat(PaymentMethodRules.allowsChange("card") || PaymentMethodRules.opensDrawer("card")).isFalse();
    assertThat(PaymentMethodRules.roundCash(1234.6, "none")).isEqualTo(1234.6);
    assertThat(PaymentMethodRules.roundCash(1234.6, "0.25")).isEqualTo(1234.5);
    assertThat(PaymentMethodRules.roundCash(1234.63, "0.25")).isEqualTo(1234.75);
    assertThat(PaymentMethodRules.roundCash(1234.6, "1")).isEqualTo(1235);
    assertThat(PaymentMethodRules.netReceived(1000, 1.6)).isEqualTo(984);
  }

  @Test
  void validatesPromptPayIds() {
    assertThat(PaymentMethodRules.isValidPromptPayId("0812345678")).isTrue();
    assertThat(PaymentMethodRules.isValidPromptPayId("081-234-5678")).isTrue();
    assertThat(PaymentMethodRules.isValidPromptPayId("0105550123451")).isTrue();
    assertThat(PaymentMethodRules.isValidPromptPayId("0105550123452")).isFalse();
    assertThat(PaymentMethodRules.isValidPromptPayId("12345")).isFalse();
  }

  @Test
  void clearsFieldsTheTypeDoesNotUse() {
    PaymentMethod m = method(3, "cash", "M-3");
    m = with(with(with(with(with(with(m, "feePercent", 2.0), "requireReference", true), "referenceLabel", "x"),
        "promptPayId", "0812345678"), "installmentMonths", List.of(3)), "cashRounding", "1");
    PaymentMethod p = PaymentMethodRules.normalize(m);
    assertThat(p.feePercent()).isZero();
    assertThat(p.requireReference()).isFalse();
    assertThat(p.referenceLabel()).isEmpty();
    assertThat(p.promptPayId()).isEmpty();
    assertThat(p.installmentMonths()).isEmpty();
    assertThat(p.cashRounding()).isEqualTo("1");
    PaymentMethod inst = PaymentMethodRules.normalize(with(method(4, "installment", "M-4"), "installmentMonths",
        new ArrayList<>(List.of(10, 3, 3, 6))));
    assertThat(inst.installmentMonths()).containsExactly(3, 6, 10);
    assertThat(inst.cashRounding()).isEqualTo("none");
  }

  @Test
  void checksCashAtShiftCloseAlwaysOthersBySettingOrDefault() {
    assertThat(PaymentMethodRules.closeCheckOf("cash", "none")).isEqualTo("count");
    assertThat(PaymentMethodRules.closeCheckOf("card", null)).isEqualTo("count");
    assertThat(PaymentMethodRules.closeCheckOf("qr", null)).isEqualTo("count");
    assertThat(PaymentMethodRules.closeCheckOf("transfer", null)).isEqualTo("confirm");
    assertThat(PaymentMethodRules.closeCheckOf("card", "none")).isEqualTo("none");
  }

  @Test
  void validatesMethods() {
    assertThat(check(method(9, "card", "M-9"), null)).isNull();
    assertThat(check(method(9, "card", "CASH"), null)).contains("ซ้ำ");
    assertThat(check(with(method(9, "card", "M-9"), "name", "ชื่อยาวเกินยี่สิบตัวอักษรแน่นอน"), null)).contains("ไม่เกิน 20");
    assertThat(check(with(with(method(9, "card", "M-9"), "minAmount", 500.0), "maxAmount", 100.0), null)).contains("ยอดสูงสุด");
    assertThat(check(with(method(9, "card", "M-9"), "feePercent", 11.0), null)).contains("ค่าธรรมเนียม");
    assertThat(check(with(with(method(9, "card", "M-9"), "requireReference", true), "referenceLabel", " "), null)).contains("เลขอ้างอิง");
    assertThat(check(with(method(9, "qr", "M-9"), "promptPayId", "123"), null)).contains("PromptPay");
    assertThat(check(method(9, "installment", "M-9"), null)).contains("อย่างน้อย 1");
    assertThat(check(with(method(9, "installment", "M-9"), "installmentMonths", List.of(1)), null)).contains("2 - 60");
    assertThat(check(with(method(9, "card", "M-9"), "minAmount", 1.005), null)).isEqualTo("ยอดขั้นต่ำทศนิยมไม่เกิน 2 ตำแหน่ง");
  }

  @Test
  void keepsAtLeastOneActiveCashMethod() {
    assertThat(PaymentMethodRules.isLastActiveCash(ALL, 1)).isTrue();
    assertThat(PaymentMethodRules.isLastActiveCash(List.of(CASH, method(2, "card", "M-2"), method(3, "cash", "M-3")), 1)).isFalse();
    assertThat(check(with(CASH, "active", false), 1L)).contains("เงินสด");
    assertThat(check(with(CASH, "type", "card"), 1L)).contains("เงินสด");
    assertThat(check(with(CASH, "name", "เงินสด"), 1L)).isNull();
  }

  private static final PosTerminal T1 = new PosTerminal(1, "หน้าร้าน", "E051234567890", 1L, true);
  private static final PosTerminal T2 = new PosTerminal(2, "ชั้น 2", "", 1L, true);
  private static final List<Branch> BRANCHES = List.of(
      new Branch(1, "HQ", "สำนักงานใหญ่", "00000", "x", "", "", true), new Branch(2, "B2", "b", "00001", "x", "", "", false));

  private static PosTerminal t2(Integer id, String name, String posId, Long branchId, boolean active) {
    return new PosTerminal(id, name, posId, branchId, active);
  }

  @Test
  void validatesTerminals() {
    assertThat(PosTerminalRules.posTerminalError(T2, List.of(T1), BRANCHES)).isNull();
    assertThat(PosTerminalRules.posTerminalError(t2(2, "ชั้น 2", "E059999", 1L, true), List.of(T1), BRANCHES)).isNull();
    assertThat(PosTerminalRules.posTerminalError(t2(0, "ชั้น 2", "", 1L, true), List.of(T1), BRANCHES)).isEqualTo("เลขเครื่องต้องเป็นจำนวนเต็ม 1–999");
    assertThat(PosTerminalRules.posTerminalError(t2(1, "ชั้น 2", "", 1L, true), List.of(T1), BRANCHES)).isEqualTo("มีเครื่อง 1 อยู่แล้ว");
    assertThat(PosTerminalRules.posTerminalError(t2(2, " ", "", 1L, true), List.of(T1), BRANCHES)).isEqualTo("กรุณากรอกชื่อเครื่อง");
    assertThat(PosTerminalRules.posTerminalError(t2(2, "x".repeat(41), "", 1L, true), List.of(T1), BRANCHES))
        .isEqualTo("ชื่อเครื่องต้องไม่เกิน 40 ตัวอักษร");
    assertThat(PosTerminalRules.posTerminalError(t2(2, "a", "x".repeat(31), 1L, true), List.of(T1), BRANCHES))
        .isEqualTo("POS ID ต้องไม่เกิน 30 ตัวอักษร");
    assertThat(PosTerminalRules.posTerminalError(t2(2, "a", " E051234567890 ", 1L, true), List.of(T1), BRANCHES))
        .isEqualTo("POS ID E051234567890 ใช้กับเครื่องอื่นแล้ว");
    assertThat(PosTerminalRules.posTerminalError(t2(3, "a", "", 1L, true), List.of(T1, T2), BRANCHES)).isNull();
    assertThat(PosTerminalRules.posTerminalError(t2(2, "a", "", 9L, true), List.of(T1), BRANCHES)).isEqualTo("กรุณาเลือกสาขาของเครื่อง");
    assertThat(PosTerminalRules.posTerminalError(t2(2, "a", "", 2L, true), List.of(T1), BRANCHES))
        .isEqualTo("สาขานี้ปิดใช้งานแล้ว เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน");
    assertThat(PosTerminalRules.posTerminalError(t2(2, "a", "", 2L, false), List.of(T1), BRANCHES)).isNull();
    assertThat(PosTerminalRules.normalize(T1, new PosTerminal(null, " a ", " b ", null, null)))
        .isEqualTo(new PosTerminal(1, "a", "b", 1L, true));
  }

  @Test
  void letsOnlyAnActiveRegisteredTerminalSellInItsBranch() {
    assertThat(PosTerminalRules.terminalError(1, List.of(T1, T2), null, List.of())).isNull();
    assertThat(PosTerminalRules.terminalError(null, List.of(T1), null, List.of()))
        .isEqualTo("เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า");
    assertThat(PosTerminalRules.terminalError(3, List.of(T1), null, List.of()))
        .isEqualTo("ไม่พบเครื่อง 3 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่");
    assertThat(PosTerminalRules.terminalError(2, List.of(t2(2, "ชั้น 2", "", 1L, false)), null, List.of()))
        .isEqualTo("เครื่อง 2 ถูกปิดใช้งาน กรุณาให้ผู้ดูแลตั้งค่าใหม่");
    assertThat(PosTerminalRules.terminalError(1, List.of(T1), 1L, List.of())).isNull();
    assertThat(PosTerminalRules.terminalError(1, List.of(T1), 2L, List.of()))
        .isEqualTo("เครื่อง 1 เป็นเครื่องของสาขาอื่น — สลับไปสาขาของเครื่อง หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่");
    assertThat(PosTerminalRules.terminalError(1, List.of(T1), 2L, BRANCHES))
        .isEqualTo("เครื่อง 1 เป็นเครื่องของสาขา HQ · สำนักงานใหญ่ — สลับไปสาขานั้น หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่");
    assertThat(PosTerminalRules.terminalOrderPrefix("HQ", 2, "2026-10-12")).isEqualTo("POS-HQ-2-20261012-");
  }

  @Test
  void moneyInputRule() {
    assertThat(Money.moneyProblem(null)).isEqualTo("required");
    assertThat(Money.moneyProblem(Double.NaN)).isEqualTo("required");
    assertThat(Money.moneyProblem(-0.01)).isEqualTo("negative");
    assertThat(Money.moneyProblem(1.005)).isEqualTo("decimals");
    for (double ok : new double[] {0, 1, 19.99, 350, 1e6}) {
      assertThat(Money.moneyProblem(ok)).isNull();
    }
    assertThat(Money.moneyInputError(null, "ต้นทุน")).isEqualTo("กรุณากรอกต้นทุน");
    assertThat(Money.moneyInputError(-5.0, "ต้นทุน")).isEqualTo("ต้นทุนต้องไม่ติดลบ");
    assertThat(Money.moneyInputError(0.125, "ต้นทุน")).isEqualTo("ต้นทุนทศนิยมไม่เกิน 2 ตำแหน่ง");
    assertThat(Money.moneyInputError(820.0, "ต้นทุน")).isNull();
    assertThat(Money.round2(1.005)).isEqualTo(1.0);
    // ค่าเดียวกับ node: -2.345 * 100 = -234.50000000000003 → -2.35; .5 จริงปัดขึ้นทาง +∞ (-0.125 → -0.12)
    assertThat(Money.round2(-2.345)).isEqualTo(-2.35);
    assertThat(Money.round2(-0.125)).isEqualTo(-0.12);
  }
}
