package com.an22.api.rules;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * port จาก {@code core/models/list-limit.model.ts} (ตกลง 2026-10-23): รายการไม่มี paging — รายการตามวันที่ต้องมีช่วง ≤ {@link #MAX_RANGE_DAYS}
 * วัน และทุกรายการเกิน {@link #MAX_LIST_ROWS} แถว → 400 (ไม่ตัดเงียบ ๆ). dao query ด้วย {@code LIMIT MAX_LIST_ROWS + 1}.
 */
public final class ListLimits {

  public static final int MAX_RANGE_DAYS = 366;
  public static final int MAX_LIST_ROWS = 20_000;
  /** LIMIT ของ query รายการ: เกินเพดาน 1 แถว = รู้ว่าเกิน. */
  public static final int QUERY_LIMIT = MAX_LIST_ROWS + 1;

  private ListLimits() {}

  /** ช่วงวันที่ที่ผ่านแล้ว (ทั้งสองปลายรวม). */
  public record DateRange(LocalDate from, LocalDate to) {}

  /** {@code dateRangeError()}: ต้องครบสองปลาย และนับรวมไม่เกิน 366 วัน — null = ผ่าน. */
  public static String dateRangeError(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      return "กรุณาเลือกช่วงวันที่ (ไม่เกิน " + MAX_RANGE_DAYS + " วัน)";
    }
    return from.plusDays(MAX_RANGE_DAYS - 1).isBefore(to) ? "ช่วงวันที่ต้องไม่เกิน " + MAX_RANGE_DAYS + " วัน" : null;
  }

  /** {@code tooManyRowsError()}: dated = รายการที่มีช่วงวันที่. */
  public static String tooManyRowsError(boolean dated) {
    return "ข้อมูลมากกว่า 20,000 รายการ กรุณา" + (dated ? "เลือกช่วงวันที่ให้สั้นลง" : "กรองให้แคบลง");
  }

  /** {@code rowLimitError()}: null = ไม่เกินเพดาน. */
  public static String rowLimitError(int rows, boolean dated) {
    return rows > MAX_LIST_ROWS ? tooManyRowsError(dated) : null;
  }

  /** ผลของ {@link #parseRange}: ช่วงที่ใช้ได้ หรือข้อความ 400. */
  public record ParsedRange(DateRange range, String error) {}

  /**
   * {@code ?from=&to=} ของรายการตามวันที่ (= {@code rangeParamsError()} ของ mock): รูปแบบ 'YYYY-MM-DD' → from ≤ to → {@link #dateRangeError}.
   */
  public static ParsedRange parseRange(String from, String to) {
    LocalDate f;
    LocalDate t;
    try {
      f = date(from);
      t = date(to);
    } catch (DateTimeParseException e) {
      return new ParsedRange(null, "รูปแบบวันที่ต้องเป็น YYYY-MM-DD");
    }
    if (f != null && t != null && f.isAfter(t)) {
      return new ParsedRange(null, "วันที่เริ่มต้องไม่เกินวันที่สิ้นสุด");
    }
    String problem = dateRangeError(f, t);
    return problem != null ? new ParsedRange(null, problem) : new ParsedRange(new DateRange(f, t), null);
  }

  private static LocalDate date(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw new DateTimeParseException("format", value, 0);
    }
    return LocalDate.parse(value);
  }
}
