package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ตาม {@code list-limit.model.spec.ts} + ลำดับตรวจของ {@code rangeParamsError()} ใน mock. */
class ListLimitsTest {

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  @Test
  void needsBothEndsAndAtMost366DaysCountingBothEnds() {
    assertThat(ListLimits.dateRangeError(null, d("2026-10-23"))).isEqualTo("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)");
    assertThat(ListLimits.dateRangeError(d("2026-10-23"), null)).isEqualTo("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)");
    assertThat(ListLimits.dateRangeError(d("2026-01-01"), d("2026-12-31"))).isNull();
    assertThat(ListLimits.dateRangeError(d("2028-01-01"), d("2028-12-31"))).isNull();
    assertThat(ListLimits.dateRangeError(d("2026-01-01"), d("2027-01-01"))).isNull();
    assertThat(ListLimits.dateRangeError(d("2026-01-01"), d("2027-01-02"))).isEqualTo("ช่วงวันที่ต้องไม่เกิน 366 วัน");
  }

  @Test
  void refusesMoreRowsThanTheCeiling() {
    assertThat(ListLimits.tooManyRowsError(true)).isEqualTo("ข้อมูลมากกว่า 20,000 รายการ กรุณาเลือกช่วงวันที่ให้สั้นลง");
    assertThat(ListLimits.tooManyRowsError(false)).isEqualTo("ข้อมูลมากกว่า 20,000 รายการ กรุณากรองให้แคบลง");
    assertThat(ListLimits.rowLimitError(20_000, true)).isNull();
    assertThat(ListLimits.rowLimitError(20_001, false)).isEqualTo(ListLimits.tooManyRowsError(false));
  }

  @Test
  void parsesFormatThenOrderThenRange() {
    assertThat(ListLimits.parseRange("2026-1-1", "2026-02-01").error()).isEqualTo("รูปแบบวันที่ต้องเป็น YYYY-MM-DD");
    assertThat(ListLimits.parseRange("2026-02-30", "2026-03-01").error()).isEqualTo("รูปแบบวันที่ต้องเป็น YYYY-MM-DD");
    assertThat(ListLimits.parseRange("2026-03-01", "2026-02-01").error()).isEqualTo("วันที่เริ่มต้องไม่เกินวันที่สิ้นสุด");
    assertThat(ListLimits.parseRange("", "2026-02-01").error()).isEqualTo("กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)");
    assertThat(ListLimits.parseRange("2026-01-01", "2026-02-01").range())
        .isEqualTo(new ListLimits.DateRange(d("2026-01-01"), d("2026-02-01")));
  }
}
