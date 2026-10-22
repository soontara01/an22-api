package com.an22.api.rules;

/** port จาก {@code core/models/money.model.ts} + การปัดเศษแบบ JS ที่กฎทุกตัวใช้. */
public final class Money {

  private Money() {}

  /** {@code Math.round(n * 100) / 100} ของ JS — .5 ปัดขึ้นทาง +∞ (Java {@code Math.round(double)} ทำแบบเดียวกัน). */
  public static double round2(double n) {
    return Math.round(n * 100) / 100.0;
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
