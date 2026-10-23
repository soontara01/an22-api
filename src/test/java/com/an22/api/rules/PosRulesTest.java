package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Category;
import com.an22.api.model.ManualDiscount;
import com.an22.api.model.Promotion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตัวช่วยของ {@link PosPricing} / {@link PosPayment} / {@link PromotionRules} ที่ fixtures ไม่ได้เรียกตรง ๆ. */
class PosRulesTest {

  private static Promotion free(double minQty, double minAmount, boolean repeat, Double maxSets) {
    return new Promotion(1L, "F", "F", "", "free_goods", "2026-01-01", null, true, 0.0, false, new Promotion.Scope(true, List.of(), List.of()),
        minQty, minAmount, null, new Promotion.FreeGoods(List.of(new Promotion.FreeItem(2L, 1.0)), repeat, maxSets), List.of());
  }

  @Test
  void freeSetsByPiecesOrAmount() {
    assertThat(PosPricing.freeSets(free(2, 0, true, null), 5, 0)).isEqualTo(2);
    assertThat(PosPricing.freeSets(free(2, 0, false, null), 5, 0)).isEqualTo(1);
    assertThat(PosPricing.freeSets(free(2, 0, true, 1.0), 9, 0)).isEqualTo(1);
    assertThat(PosPricing.freeSets(free(0, 1000, true, null), 0, 2999.99)).isEqualTo(2);
    // 0.1 + 0.2 ไม่ใช่ 0.3 พอดี — ค่าเผื่อ 1e-9 ทำให้ครบชุด
    assertThat(PosPricing.freeSets(free(0, 0.3, true, null), 0, 0.1 + 0.2)).isEqualTo(1);
    assertThat(PosPricing.freeSets(free(0, 0, true, null), 9, 9)).isZero();
  }

  @Test
  void discountsAndScope() {
    assertThat(PromotionRules.discountAmount(1000, new Promotion.Discount("percent", 15.0, 100.0))).isEqualTo(100);
    assertThat(PromotionRules.discountAmount(99.99, new Promotion.Discount("percent", 10.0, null))).isEqualTo(10);
    assertThat(PromotionRules.discountAmount(300, new Promotion.Discount("amount", 500.0, null))).isEqualTo(300);
    assertThat(PromotionRules.discountAmount(0, new Promotion.Discount("amount", 5.0, null))).isZero();
    List<Category> cats = List.of(new Category(1, "IT", "ไอที", null, 1, true, 0), new Category(2, "IT-A", "อุปกรณ์", 1L, 2, true, 0));
    Promotion byRoot = new Promotion(1L, "P", "P", "", "item_discount", "2026-01-01", null, true, 0.0, false,
        new Promotion.Scope(false, List.of(), List.of(1L)), 1.0, 0.0, new Promotion.Discount("percent", 10.0, null), null, List.of());
    assertThat(PromotionRules.inScope(byRoot, 9, 2L, cats)).isTrue();
    assertThat(PromotionRules.inScope(byRoot, 9, null, cats)).isFalse();
  }

  @Test
  void manualDiscountValues() {
    assertThat(PosPricing.manualDiscountValueError(new ManualDiscount("percent", 100.0))).isNull();
    assertThat(PosPricing.manualDiscountValueError(new ManualDiscount("percent", 0.0))).isEqualTo("ส่วนลดพิเศษต้องมากกว่า 0 และไม่เกิน 100%");
    assertThat(PosPricing.manualDiscountValueError(new ManualDiscount("amount", 1.005))).isEqualTo("จำนวนเงินส่วนลดพิเศษไม่ถูกต้อง");
    assertThat(PosPricing.manualDiscountBaht(new ManualDiscount("percent", 10.0), 33.33)).isEqualTo(3.33);
    assertThat(PosPricing.manualDiscountBaht(new ManualDiscount("amount", 50.0), 20)).isEqualTo(20);
  }

  @Test
  void belowCostIssuesKeepOnlyTheWarning() {
    List<PosPricing.CartIssue> issues = List.of(
        new PosPricing.CartIssue(0, 1, "A " + PosPricing.BELOW_COST_WARNING, false),
        new PosPricing.CartIssue(1, 2, "B สต็อกไม่พอ", true),
        new PosPricing.CartIssue(null, 3, "ของแถม C สต็อกไม่พอ แถมได้ 0 จาก 1 ชิ้น", false));
    assertThat(PosPricing.belowCostIssues(issues)).extracting(PosPricing.CartIssue::productId).containsExactly(1L);
  }

  @Test
  void cashAndBaht() {
    assertThat(PosPayment.cashDueFor(100.1, "0.25")).isEqualTo(100.0);
    assertThat(PosPayment.cashDueFor(100.13, "0.25")).isEqualTo(100.25);
    assertThat(PosPayment.cashDueFor(-5, "1")).isZero();
    assertThat(PosPayment.baht(1234.5)).isEqualTo("฿1,234.5");
    assertThat(PosPayment.baht(1000)).isEqualTo("฿1,000");
    assertThat(PosPayment.baht(0.125)).isEqualTo("฿0.13");
  }
}
