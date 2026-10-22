package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.StockCardResult;
import com.an22.api.model.StockCardResult.Balance;
import com.an22.api.model.StockCardResult.Totals;
import com.an22.api.model.StockMovement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code costing.model.spec.ts} + {@code serial.model.spec.ts} (format). */
class CostingTest {

  private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

  @Test
  void reAveragesCostOnReceipt() {
    assertThat(Costing.movingAverage(0, 0, 10, 80)).isEqualTo(80);
    assertThat(Costing.movingAverage(10, 80, 10, 100)).isEqualTo(90);
    assertThat(Costing.movingAverage(3, 350, 20, 300)).isEqualTo(306.5217);
    assertThat(Costing.movingAverage(-2, 50, 5, 60)).isEqualTo(60);
  }

  @Test
  void effectiveCostAndValue() {
    assertThat(Costing.effectiveCost(5, 90, 100)).isEqualTo(90);
    assertThat(Costing.effectiveCost(0, 90, 100)).isEqualTo(100);
    assertThat(Costing.effectiveCost(5, 0, 100)).isEqualTo(100);
    assertThat(Costing.costValue(3, 306.5217)).isEqualTo(919.57);
  }

  private static StockMovement m(long id, String day, int qty, int balanceQty, double avg) {
    return m(id, day, qty > 0 ? "receive" : "issue", qty, balanceQty, avg);
  }

  private static StockMovement m(long id, String day, String type, int qty, int balanceQty, double avg) {
    return new StockMovement(id, 1, 1, ZonedDateTime.of(LocalDate.parse(day).atTime(12, 0), BKK).toInstant(), type, qty, avg,
        qty * avg, balanceQty, avg, balanceQty * avg, List.of(), "");
  }

  private static final List<StockMovement> MOVES = List.of(
      m(1, "2026-09-01", 10, 10, 100), m(2, "2026-09-15", -4, 6, 100), m(3, "2026-10-01", 4, 10, 90), m(4, "2026-10-03", -2, 8, 90));

  @Test
  void derivesOpeningMovementsTotalsAndClosing() {
    StockCardResult card = Costing.buildStockCard(MOVES, LocalDate.parse("2026-09-10"), LocalDate.parse("2026-10-01"), BKK);
    assertThat(card.opening()).isEqualTo(new Balance(10, 100, 1000));
    assertThat(card.movements()).extracting(StockMovement::id).containsExactly(2L, 3L);
    assertThat(card.totals()).isEqualTo(new Totals(4, 360, 4, 400));
    assertThat(card.closing()).isEqualTo(new Balance(10, 90, 900));
  }

  @Test
  void treatsOpeningLinesAsBroughtForward() {
    List<StockMovement> card = List.of(m(1, "2026-10-01", "opening", 25, 25, 850), m(2, "2026-10-05", 3, 28, 850),
        m(3, "2026-10-06", -1, 27, 850));
    for (LocalDate[] range : new LocalDate[][] {{LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")}, {null, null}}) {
      StockCardResult c = Costing.buildStockCard(card, range[0], range[1], BKK);
      assertThat(c.opening()).isEqualTo(new Balance(25, 850, 21250));
      assertThat(c.movements()).extracting(StockMovement::id).containsExactly(2L, 3L);
      assertThat(c.openingMovements()).extracting(StockMovement::id).containsExactly(1L);
      assertThat(c.totals()).isEqualTo(new Totals(3, 2550, 1, 850));
    }
    StockCardResult only = Costing.buildStockCard(card, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), BKK);
    assertThat(only.movements()).isEmpty();
    assertThat(only.closing().qty()).isEqualTo(25);
  }

  @Test
  void zeroOpeningWithoutFromAndClosingEqualsOpeningWhenEmpty() {
    assertThat(Costing.buildStockCard(MOVES, null, null, BKK).opening()).isEqualTo(new Balance(0, 0, 0));
    StockCardResult empty = Costing.buildStockCard(MOVES, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-25"), BKK);
    assertThat(empty.movements()).isEmpty();
    assertThat(empty.closing()).isEqualTo(empty.opening());
    assertThat(empty.opening().qty()).isEqualTo(6);
  }

  @Test
  void serialFormat() {
    assertThat(SerialRules.serialFormatError("NB-1", "NB", 4)).isNull();
    assertThat(SerialRules.serialFormatError("nb-1", "NB", null)).isEqualTo("ใช้ได้เฉพาะ A-Z, 0-9 และ -");
    assertThat(SerialRules.serialFormatError("XX-1", "NB", null)).isEqualTo("ต้องขึ้นต้นด้วย NB");
    assertThat(SerialRules.serialFormatError("NB-12", "NB", 4)).isEqualTo("ต้องยาว 4 ตัว (ตอนนี้ 5)");
  }

  @Test
  void vatAndStatus() {
    assertThat(ProductRules.vatBreakdown(107, "vat7")).containsExactly(100, 7);
    assertThat(ProductRules.vatBreakdown(100, "exempt")).containsExactly(100, 0);
    assertThat(ProductRules.canSell("no_purchase")).isTrue();
    assertThat(ProductRules.canPurchase("no_purchase")).isFalse();
    assertThat(ProductRules.purchaseError("discontinued")).isEqualTo("สถานะ \"เลิกจำหน่าย\" ห้ามรับเข้าสินค้า");
    assertThat(ProductRules.receiptCostError(null)).isEqualTo("กรุณากรอกต้นทุน");
    assertThat(ProductRules.receiptCostError(-1.0)).isEqualTo("ต้นทุนต้องไม่ติดลบ");
    assertThat(ProductRules.receiptCostError(12.345)).isNull();
  }
}
