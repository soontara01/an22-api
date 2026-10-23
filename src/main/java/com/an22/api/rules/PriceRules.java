package com.an22.api.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** port จาก {@code core/models/price.model.ts} + ตรวจของ {@code handlePrices} ใน mock — วันที่เป็น 'YYYY-MM-DD' เทียบแบบ string เหมือน TS. */
public final class PriceRules {

  /** แทนวันสิ้นสุดที่ไม่กำหนด (ISO เทียบแบบ string ได้). */
  static final String OPEN_END = "9999-12-31";

  private static final String[] THAI_MONTHS = {
    "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
  };

  private PriceRules() {}

  /** ช่วงวันของราคา (หรือโปร) — endDate null = ไม่กำหนด. */
  public record Range(String startDate, String endDate) {}

  /** ช่วงราคาที่มีอยู่ (สำหรับ {@link #findOverlap}). */
  public record Period(long id, long productId, double price, String startDate, String endDate) {
    Range range() {
      return new Range(startDate, endDate);
    }
  }

  /** {@code priceStatus()}: active / scheduled / expired. */
  public static String priceStatus(Range p, String today) {
    if (p.startDate().compareTo(today) > 0) {
      return "scheduled";
    }
    if (p.endDate() != null && p.endDate().compareTo(today) < 0) {
      return "expired";
    }
    return "active";
  }

  /** {@code rangesOverlap()}: รวมปลายทั้งสองข้าง, null = ไม่สิ้นสุด. */
  public static boolean rangesOverlap(Range a, Range b) {
    return a.startDate().compareTo(end(b)) <= 0 && b.startDate().compareTo(end(a)) <= 0;
  }

  /** {@code findOverlap()}: ช่วงแรกของ SKU เดียวกันที่ทับ (ไม่นับ {@code exceptId} = ตัวที่กำลังแก้). */
  public static Optional<Period> findOverlap(List<Period> prices, long productId, Range candidate, Long exceptId) {
    return prices.stream()
        .filter(p -> p.productId() == productId && (exceptId == null || p.id() != exceptId) && rangesOverlap(p.range(), candidate))
        .findFirst();
  }

  /** {@code formatDateRange()}: เช่น '1 ก.ค. 2569 – ไม่กำหนด' (Intl th-TH day numeric / month short / year numeric). */
  public static String formatDateRange(Range p) {
    return thaiDate(p.startDate()) + " – " + (p.endDate() != null ? thaiDate(p.endDate()) : "ไม่กำหนด");
  }

  /** 'YYYY-MM-DD' ที่เป็นวันจริง — ไม่ใช่ = null (TS ไม่ตรวจ; ฝั่งเราต้องเก็บลง {@code date}). */
  public static LocalDate parseDate(String iso) {
    if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return null;
    }
    try {
      return LocalDate.parse(iso);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * ตรวจของ {@code handlePrices.validate} (หลังรู้ว่า SKU มีจริง): ราคา, วันที่เริ่ม, ลำดับวัน, ช่วงทับ — null = ผ่าน.
   * วันที่รูปแบบผิดตอบ 'รูปแบบวันที่ไม่ถูกต้อง' (ข้อความเดียวกับ {@code promotionError()}).
   */
  public static String priceError(Double price, long productId, String startDate, String endDate, List<Period> prices, Long exceptId) {
    String priceProblem = Money.moneyInputError(price, "ราคา");
    if (priceProblem != null) {
      return priceProblem;
    }
    if (startDate == null || startDate.isEmpty()) {
      return "กรุณาระบุวันที่เริ่ม";
    }
    if (parseDate(startDate) == null || (endDate != null && parseDate(endDate) == null)) {
      return "รูปแบบวันที่ไม่ถูกต้อง";
    }
    if (endDate != null && endDate.compareTo(startDate) < 0) {
      return "วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม";
    }
    return findOverlap(prices, productId, new Range(startDate, endDate), exceptId)
        .map(o -> "ช่วงวันที่ทับกับราคา ฿" + SubscriptionRules.thaiNumber(BigDecimal.valueOf(o.price())) + " ("
            + formatDateRange(o.range()) + ")")
        .orElse(null);
  }

  /** {@code String(n)} ของ JS: 26900 → "26900", 199.5 → "199.5" (ป้ายของ audit). */
  public static String jsNumber(double n) {
    return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
  }

  private static String end(Range r) {
    return r.endDate() != null ? r.endDate() : OPEN_END;
  }

  private static String thaiDate(String iso) {
    LocalDate d = LocalDate.parse(iso);
    return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
  }
}
