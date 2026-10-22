package com.an22.api.rules;

import com.an22.api.model.PaymentMethod;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** port จาก {@code core/models/payment-method.model.ts} — ข้อความไทยตรงทุกตัวอักษร. */
public final class PaymentMethodRules {

  public static final int PAYMENT_NAME_MAX = 20;
  public static final int PAYMENT_FEE_MAX = 10;
  public static final List<String> TYPES = List.of("cash", "card", "qr", "transfer", "e_wallet", "installment", "other");
  /** {@code DEFAULT_CLOSE_CHECK}: ช่องทางที่มียอดรวมที่เคาน์เตอร์ตรวจแบบนับ. */
  public static final Map<String, String> DEFAULT_CLOSE_CHECK =
      Map.of("cash", "count", "card", "count", "installment", "count", "qr", "count",
          "transfer", "confirm", "e_wallet", "confirm", "other", "confirm");

  private PaymentMethodRules() {}

  /** {@code closeCheckOf()}: เงินสดนับเสมอ, อื่นตามที่ตั้ง / ค่าเริ่มของประเภท. */
  public static String closeCheckOf(String type, String closeCheck) {
    if ("cash".equals(type)) {
      return "count";
    }
    return closeCheck != null ? closeCheck : DEFAULT_CLOSE_CHECK.getOrDefault(type, "confirm");
  }

  public static boolean allowsChange(String type) {
    return "cash".equals(type);
  }

  public static boolean opensDrawer(String type) {
    return "cash".equals(type);
  }

  /** {@code roundCash()}: ปัดยอดเงินสดเป็นขั้นที่ใกล้ที่สุด. */
  public static double roundCash(double amount, String rounding) {
    if ("none".equals(rounding)) {
      return Money.round2(amount);
    }
    double step = "1".equals(rounding) ? 1 : 0.25;
    return Money.round2(Math.round(amount / step) * step);
  }

  /** {@code netReceived()}: ยอดที่ร้านได้หลังค่าธรรมเนียม. */
  public static double netReceived(double amount, double feePercent) {
    return Money.round2(amount * (1 - feePercent / 100));
  }

  /** {@code isValidPromptPayId()}: เบอร์มือถือ 10 หลัก (ขึ้นต้น 0) หรือเลขผู้เสียภาษี 13 หลักที่ถูกต้อง. */
  public static boolean isValidPromptPayId(String id) {
    String digits = id.replaceAll("[\\s-]", "");
    return digits.matches("0\\d{9}") || (digits.length() == 13 && Suppliers.isValidThaiTaxId(digits));
  }

  /**
   * ค่าที่ไม่ส่ง = {@code PAYMENT_METHOD_DEFAULTS}, code ตัวใหญ่, ชื่อตัดช่องว่าง, {@code maxAmount} 0 = ไม่จำกัด, แล้ว
   * {@code withPaymentTypeRules()} (ล้าง field ที่ประเภทนี้ไม่ใช้) — {@code normalize()} ของ mock.
   */
  public static PaymentMethod normalize(PaymentMethod m) {
    String type = m.type();
    boolean cash = "cash".equals(type);
    boolean requireRef = !cash && Boolean.TRUE.equals(m.requireReference());
    String promptPay = m.promptPayId() == null ? "" : m.promptPayId();
    String bank = m.bankAccount() == null ? "" : m.bankAccount();
    List<Integer> months = m.installmentMonths() == null ? List.of() : m.installmentMonths();
    return new PaymentMethod(
        null,
        m.code() == null ? "" : m.code().strip().toUpperCase(Locale.ROOT),
        m.name() == null ? "" : m.name().strip(),
        type,
        m.active() == null || m.active(),
        m.sortOrder() == null ? 0 : m.sortOrder(),
        requireRef,
        requireRef ? (m.referenceLabel() == null ? "" : m.referenceLabel().strip()) : "",
        m.minAmount() == null ? 0.0 : m.minAmount(),
        m.maxAmount() == null || m.maxAmount() == 0 ? null : m.maxAmount(),
        cash ? 0.0 : (m.feePercent() == null ? 0.0 : m.feePercent()),
        cash ? (m.cashRounding() == null ? "none" : m.cashRounding()) : "none",
        "qr".equals(type) ? promptPay.replaceAll("[\\s-]", "") : "",
        "qr".equals(type) || "transfer".equals(type) ? bank.strip() : "",
        "installment".equals(type) ? months.stream().filter(Objects::nonNull).distinct().sorted().toList() : List.of(),
        closeCheckOf(type, m.closeCheck()),
        m.note() == null ? "" : m.note());
  }

  private static boolean isActiveCash(PaymentMethod m) {
    return "cash".equals(m.type()) && Boolean.TRUE.equals(m.active());
  }

  /** {@code isLastActiveCash()}: เป็นเงินสดที่ใช้งานอยู่ตัวเดียว — ปิด / เปลี่ยนประเภท / ลบไม่ได้. */
  public static boolean isLastActiveCash(List<PaymentMethod> methods, long id) {
    PaymentMethod target = methods.stream().filter(m -> m.id() == id).findFirst().orElse(null);
    return target != null && isActiveCash(target) && methods.stream().noneMatch(m -> m.id() != id && isActiveCash(m));
  }

  /** {@code paymentMethodError()} — payload ผ่าน {@link #normalize} แล้ว; {@code exceptId} = ช่องทางที่แก้. */
  public static String paymentMethodError(PaymentMethod p, List<PaymentMethod> methods, Long exceptId) {
    if (!p.code().matches("[A-Z0-9-]{1,20}")) {
      return "รหัสใช้ได้เฉพาะ A-Z, 0-9 และ - (ไม่เกิน 20 ตัว)";
    }
    if (methods.stream().anyMatch(m -> !m.id().equals(exceptId) && m.code().equals(p.code()))) {
      return "รหัส " + p.code() + " ซ้ำ";
    }
    String name = p.name().strip();
    if (name.isEmpty()) {
      return "กรุณากรอกชื่อช่องทางชำระเงิน";
    }
    if (name.length() > PAYMENT_NAME_MAX) {
      return "ชื่อต้องไม่เกิน " + PAYMENT_NAME_MAX + " ตัวอักษร";
    }
    if (!TYPES.contains(p.type())) {
      return "ประเภทไม่ถูกต้อง";
    }
    String min = Money.moneyInputError(p.minAmount(), "ยอดขั้นต่ำ");
    if (min != null) {
      return min;
    }
    if (p.maxAmount() != null && !(p.maxAmount() > 0 && p.maxAmount() >= p.minAmount())) {
      return "ยอดสูงสุดต้องมากกว่า 0 และไม่น้อยกว่ายอดขั้นต่ำ";
    }
    if (!(p.feePercent() >= 0 && p.feePercent() <= PAYMENT_FEE_MAX)) {
      return "ค่าธรรมเนียมต้องอยู่ระหว่าง 0 - " + PAYMENT_FEE_MAX + "%";
    }
    if (p.requireReference() && p.referenceLabel().isEmpty()) {
      return "กรุณาระบุชื่อช่องเลขอ้างอิง";
    }
    if ("qr".equals(p.type()) && !isValidPromptPayId(p.promptPayId())) {
      return "PromptPay ID ต้องเป็นเบอร์มือถือ 10 หลัก หรือเลขผู้เสียภาษี 13 หลักที่ถูกต้อง";
    }
    if ("installment".equals(p.type())) {
      if (p.installmentMonths().isEmpty()) {
        return "กรุณาระบุจำนวนเดือนที่ผ่อนได้อย่างน้อย 1 ตัวเลือก";
      }
      if (p.installmentMonths().stream().anyMatch(n -> n < 2 || n > 60)) {
        return "จำนวนเดือนผ่อนต้องเป็นจำนวนเต็ม 2 - 60";
      }
    }
    if (exceptId != null && isLastActiveCash(methods, exceptId) && !isActiveCash(p)) {
      return "ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง";
    }
    return null;
  }
}
