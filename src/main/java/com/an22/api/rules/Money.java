package com.an22.api.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** port จาก {@code core/models/money.model.ts} + การปัดเศษแบบ JS ที่กฎทุกตัวใช้. */
public final class Money {

  private Money() {}

  /** {@code Math.round(n * 100) / 100} ของ JS — .5 ปัดขึ้นทาง +∞ (Java {@code Math.round(double)} ทำแบบเดียวกัน). */
  public static double round2(double n) {
    return Math.round(n * 100) / 100.0;
  }

  /**
   * {@code n.toLocaleString('en-US', { maximumFractionDigits: 2 })}: คั่นหลักพัน, ทศนิยมไม่เกิน 2 ไม่เติม 0, ปัดครึ่งออกจากศูนย์ (halfExpand)
   * บนค่าทศนิยมที่สั้นที่สุดของ double (เหมือน JS).
   */
  public static String enNumber(double n) {
    DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(BigDecimal.valueOf(n));
  }

  /** {@code moneyProblem()}: null = ไม่ได้กรอก, ติดลบ, ทศนิยมเกิน 2 — null ผลลัพธ์ = ถูก. */
  public static String moneyProblem(Double value) {
    if (value == null || !Double.isFinite(value)) {
      return "required";
    }
    if (value < 0) {
      return "negative";
    }
    return Math.round(value * 100) / 100.0 == value ? null : "decimals";
  }

  /** {@code moneyInputError()}: ข้อความไทยของ {@link #moneyProblem}, เช่น label "ต้นทุน" → "กรุณากรอกต้นทุน". */
  public static String moneyInputError(Double value, String label) {
    String problem = moneyProblem(value);
    if (problem == null) {
      return null;
    }
    return switch (problem) {
      case "required" -> "กรุณากรอก" + label;
      case "negative" -> label + "ต้องไม่ติดลบ";
      default -> label + "ทศนิยมไม่เกิน 2 ตำแหน่ง";
    };
  }
}
