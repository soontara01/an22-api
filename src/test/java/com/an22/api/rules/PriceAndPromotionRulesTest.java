package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Promotion;
import com.an22.api.rules.PriceRules.Period;
import com.an22.api.rules.PriceRules.Range;
import com.an22.api.rules.PromotionRules.BranchRef;
import com.an22.api.rules.PromotionRules.CategoryRef;
import com.an22.api.rules.PromotionRules.Context;
import com.an22.api.rules.PromotionRules.ProductRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ตาม {@code price.model.spec.ts}, {@code promotion.model.spec.ts} (ส่วน master) + ข้อความของ {@code handlePrices} ใน mock. */
class PriceAndPromotionRulesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  // ---------- ราคา ----------

  private static Period p(long id, String start, String end, long productId) {
    return new Period(id, productId, id * 100, start, end);
  }

  private static Period p(long id, String start, String end) {
    return p(id, start, end, 1);
  }

  private static Range range(Period x) {
    return new Range(x.startDate(), x.endDate());
  }

  @Test
  void priceStatusWithInclusiveEndsAndOpenEnd() {
    String today = "2026-10-03";
    assertThat(PriceRules.priceStatus(range(p(1, "2026-10-03", "2026-10-03")), today)).isEqualTo("active");
    assertThat(PriceRules.priceStatus(range(p(1, "2026-10-04", null)), today)).isEqualTo("scheduled");
    assertThat(PriceRules.priceStatus(range(p(1, "2026-01-01", "2026-10-02")), today)).isEqualTo("expired");
    assertThat(PriceRules.priceStatus(range(p(1, "2026-01-01", null)), today)).isEqualTo("active");
  }

  @Test
  void overlapsTouchingDaysButNotAdjacentOnes() {
    Range a = range(p(1, "2026-01-01", "2026-06-30"));
    assertThat(PriceRules.rangesOverlap(a, range(p(2, "2026-06-30", "2026-12-31")))).isTrue();
    assertThat(PriceRules.rangesOverlap(a, range(p(2, "2026-07-01", null)))).isFalse();
    assertThat(PriceRules.rangesOverlap(range(p(1, "2026-01-01", null)), range(p(2, "2030-01-01", "2030-01-31")))).isTrue();
  }

  @Test
  void findsOverlapsOfTheSameSkuIgnoringTheEditedOne() {
    List<Period> prices = List.of(p(1, "2026-01-01", "2026-06-30"), p(2, "2026-07-01", null), p(3, "2026-01-01", null, 99));
    Range candidate = new Range("2026-08-01", "2026-08-31");
    assertThat(PriceRules.findOverlap(prices, 1, candidate, null)).map(Period::id).contains(2L);
    assertThat(PriceRules.findOverlap(prices, 1, candidate, 2L)).isEmpty();
  }

  @Test
  void priceErrorsUseTheMockMessages() {
    List<Period> prices = List.of(new Period(1, 1, 26900, "2026-01-01", "2026-06-30"), new Period(2, 1, 1234.5, "2026-07-01", null));
    assertThat(PriceRules.priceError(null, 1, "2027-01-01", null, prices, null)).isEqualTo("กรุณากรอกราคา");
    assertThat(PriceRules.priceError(-1.0, 1, "2027-01-01", null, prices, null)).isEqualTo("ราคาต้องไม่ติดลบ");
    assertThat(PriceRules.priceError(1.005, 1, "2027-01-01", null, prices, null)).isEqualTo("ราคาทศนิยมไม่เกิน 2 ตำแหน่ง");
    assertThat(PriceRules.priceError(1.0, 1, null, null, prices, null)).isEqualTo("กรุณาระบุวันที่เริ่ม");
    assertThat(PriceRules.priceError(1.0, 1, "2026-02-30", null, prices, null)).isEqualTo("รูปแบบวันที่ไม่ถูกต้อง");
    assertThat(PriceRules.priceError(1.0, 1, "2026-03-01", "2026-02-28", prices, null)).isEqualTo("วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม");
    assertThat(PriceRules.priceError(1.0, 1, "2026-06-30", "2026-06-30", prices, null))
        .isEqualTo("ช่วงวันที่ทับกับราคา ฿26,900 (1 ม.ค. 2569 – 30 มิ.ย. 2569)");
    assertThat(PriceRules.priceError(1.0, 1, "2030-01-01", null, prices, null))
        .isEqualTo("ช่วงวันที่ทับกับราคา ฿1,234.5 (1 ก.ค. 2569 – ไม่กำหนด)");
    assertThat(PriceRules.priceError(1.0, 1, "2030-01-01", null, prices, 2L)).isNull();
    assertThat(PriceRules.priceError(0.0, 2, "2026-01-01", null, prices, null)).isNull();
  }

  @Test
  void formatsLikeIntlThaiAndJsNumbers() {
    assertThat(PriceRules.formatDateRange(new Range("2026-12-01", "2027-02-28"))).isEqualTo("1 ธ.ค. 2569 – 28 ก.พ. 2570");
    assertThat(PriceRules.jsNumber(26900)).isEqualTo("26900");
    assertThat(PriceRules.jsNumber(199.5)).isEqualTo("199.5");
    assertThat(PriceRules.jsNumber(0)).isEqualTo("0");
  }

  // ---------- โปรโมชั่น ----------

  private static final List<CategoryRef> CATEGORIES = List.of(
      new CategoryRef(1, "ไอที", true), new CategoryRef(2, "อุปกรณ์", true), new CategoryRef(3, "เมาส์", true),
      new CategoryRef(4, "เก่า", false));
  private static final List<ProductRef> PRODUCTS = List.of(
      new ProductRef(1, "SKU-1", "active"), new ProductRef(2, "SKU-2", "discontinued"), new ProductRef(3, "SKU-3", "no_sale"));

  /**
   * {@code promo(extra)} ของ spec: ค่าเริ่ม ({@code PROMOTION_DEFAULTS} — ส่วนลด 10%) + id 1, PRO-1, scope SKU 1, เริ่ม 2026-10-01 — ผ่าน JSON
   * เหมือน request จริง (normalize ไม่เติม discount แล้ว — B4).
   */
  private static Promotion promo(Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", 1);
    m.put("code", "PRO-1");
    m.put("name", "โปร");
    m.put("startDate", "2026-10-01");
    m.put("scope", Map.of("all", false, "productIds", List.of(1), "categoryIds", List.of()));
    m.put("discount", Map.of("kind", "percent", "value", 10));
    m.putAll(extra);
    Promotion p = PromotionRules.normalize(JSON.convertValue(m, Promotion.class));
    return withId(p, ((Number) m.get("id")).longValue());
  }

  private static Promotion promo() {
    return promo(Map.of());
  }

  private static Promotion withId(Promotion p, Long id) {
    return new Promotion(id, p.code(), p.name(), p.note(), p.type(), p.startDate(), p.endDate(), p.enabled(), p.priority(), p.stackable(),
        p.scope(), p.minQty(), p.minAmount(), p.discount(), p.freeGoods(), p.branchIds());
  }

  private static Context ctx(String today, List<Promotion> promotions) {
    return new Context(PRODUCTS, CATEGORIES, promotions, List.of(new BranchRef(1, true), new BranchRef(2, false)), today);
  }

  private static String check(Promotion p, List<Promotion> promotions) {
    return PromotionRules.promotionError(withId(p, null), ctx("2026-09-01", promotions), null);
  }

  private static Map<String, Object> scope(List<Integer> products, List<Integer> categories) {
    return Map.of("scope", Map.of("all", false, "productIds", products, "categoryIds", categories));
  }

  @Test
  void statusFromDatesAndEnabledFlag() {
    assertThat(PromotionRules.promotionStatus(true, "2026-10-01", "2026-10-31", "2026-09-30")).isEqualTo("scheduled");
    assertThat(PromotionRules.promotionStatus(true, "2026-10-01", "2026-10-31", "2026-10-31")).isEqualTo("active");
    assertThat(PromotionRules.promotionStatus(true, "2026-10-01", "2026-10-31", "2026-11-01")).isEqualTo("expired");
    assertThat(PromotionRules.promotionStatus(false, "2026-10-01", "2026-10-31", "2026-10-15")).isEqualTo("disabled");
  }

  @Test
  void validatesNewPromotions() {
    assertThat(check(promo(), List.of())).isNull();
    assertThat(check(promo(Map.of("code", "bad code")), List.of())).contains("A-Z");
    assertThat(check(promo(), List.of(promo(Map.of("id", 9))))).contains("ซ้ำ");
    assertThat(check(promo(Map.of("endDate", "2026-09-30")), List.of())).contains("ไม่ก่อนวันที่เริ่ม");
    assertThat(check(promo(scope(List.of(), List.of())), List.of())).contains("เลือกสินค้า");
    assertThat(check(promo(scope(List.of(2), List.of())), List.of())).contains("เลิกจำหน่าย");
    assertThat(check(promo(scope(List.of(), List.of(4))), List.of())).contains("ปิดใช้งาน");
    assertThat(check(promo(Map.of("discount", discount("percent", 120, null))), List.of())).contains("ไม่เกิน 100");
    assertThat(check(promo(Map.of("discount", discount("amount", 50, 10))), List.of())).contains("เพดาน");
    assertThat(check(promo(Map.of("priority", 1.5)), List.of())).isEqualTo("ลำดับความสำคัญต้องเป็นจำนวนเต็ม 0 - 999");
    assertThat(check(promo(Map.of("minQty", 0)), List.of())).isEqualTo("จำนวนขั้นต่ำต้องอย่างน้อย 1");
  }

  @Test
  void normalizesLikeTheMock() {
    Promotion p = promo(Map.of("code", " pro-x ", "name", " ชื่อ ", "endDate", "", "type", "bill_discount"));
    assertThat(p.code()).isEqualTo("PRO-X");
    assertThat(p.name()).isEqualTo("ชื่อ");
    assertThat(p.endDate()).isNull();
    assertThat(p.scope()).isEqualTo(new Promotion.Scope(true, List.of(), List.of()));
    assertThat(p.discount()).isEqualTo(new Promotion.Discount("percent", 10.0, null));
    assertThat(p.enabled()).isTrue();
    assertThat(p.minQty()).isEqualTo(1.0);
  }

  private static Map<String, Object> discount(String kind, double value, Integer max) {
    Map<String, Object> d = new HashMap<>();
    d.put("kind", kind);
    d.put("value", value);
    d.put("maxDiscount", max);
    return d;
  }

  private static String free(List<Map<String, Object>> items, Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(Map.of("type", "free_goods"));
    Map<String, Object> goods = new HashMap<>();
    goods.put("items", items);
    goods.put("repeat", true);
    goods.put("maxSets", null);
    m.put("freeGoods", goods);
    m.put("discount", null);
    m.putAll(extra);
    return PromotionRules.promotionError(withId(promo(m), null), ctx("2026-09-01", List.of()), null);
  }

  private static Map<String, Object> item(int productId, Object qty) {
    return Map.of("productId", productId, "qty", qty);
  }

  @Test
  void validatesFreeGoods() {
    assertThat(free(List.of(item(1, 1)), Map.of())).isNull();
    assertThat(free(List.of(), Map.of())).contains("อย่างน้อย 1");
    assertThat(free(List.of(item(3, 1)), Map.of())).contains("ขายไม่ได้");
    assertThat(free(List.of(item(1, 0)), Map.of())).contains("≥ 1");
    assertThat(free(List.of(item(1, 1), item(1, 2)), Map.of())).contains("ซ้ำ");
    assertThat(free(List.of(item(1, 1)), Map.of("minQty", 0, "minAmount", 0))).contains("ขั้นต่ำ");
    assertThat(free(List.of(item(9, 1)), Map.of())).isEqualTo("ไม่พบสินค้าแถม #9");
    assertThat(free(List.of(item(1, 1)), Map.of("discount", discount("percent", 10, null)))).isEqualTo("โปรของแถมต้องไม่มีส่วนลด");
  }

  @Test
  void locksAStartedPromotionExceptEnabledShorterEndDateAndNote() {
    Promotion stored = promo(Map.of("endDate", "2026-10-31"));
    java.util.function.Function<Map<String, Object>, String> edit = changes -> {
      Map<String, Object> m = new HashMap<>(Map.of("endDate", "2026-10-31"));
      m.putAll(changes);
      return PromotionRules.promotionError(withId(promo(m), null), ctx("2026-10-10", List.of(stored)), stored);
    };
    assertThat(edit.apply(Map.of("enabled", false, "note", "หยุดชั่วคราว"))).isNull();
    assertThat(edit.apply(Map.of("endDate", "2026-10-20"))).isNull();
    assertThat(edit.apply(Map.of("endDate", "2026-11-30"))).contains("ช้าลงไม่ได้");
    assertThat(edit.apply(new HashMap<>(java.util.Collections.singletonMap("endDate", null)))).contains("ไม่กำหนดวันสิ้นสุด");
    assertThat(edit.apply(Map.of("endDate", "2026-10-05"))).contains("ก่อนวันนี้");
    assertThat(edit.apply(Map.of("name", "ชื่อใหม่"))).contains("เริ่มแล้ว");
    assertThat(edit.apply(Map.of("discount", discount("percent", 20, null)))).contains("เริ่มแล้ว");
    // ค่าเดิมที่เขียนต่างแบบ (ลำดับ key / 10 กับ 10.0) ไม่ใช่การแก้
    assertThat(edit.apply(Map.of("discount", discount("percent", 10, null)))).isNull();
    // ก่อนเริ่มแก้ได้ทุกอย่าง
    assertThat(PromotionRules.promotionError(withId(promo(Map.of("name", "ใหม่")), null), ctx("2026-09-01", List.of(stored)), stored))
        .isNull();
  }

  @Test
  void limitsAPromotionToBranches() {
    assertThat(PromotionRules.inBranch(List.of(), null)).isTrue();
    assertThat(PromotionRules.inBranch(List.of(2L), 2L)).isTrue();
    assertThat(PromotionRules.inBranch(List.of(2L), 1L)).isFalse();
    assertThat(PromotionRules.inBranch(List.of(2L), null)).isFalse();
    assertThat(check(promo(Map.of("branchIds", List.of(1, 1))), List.of())).isEqualTo("เลือกสาขาซ้ำกัน");
    assertThat(check(promo(Map.of("branchIds", List.of(2))), List.of())).isEqualTo("สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว");
    assertThat(check(promo(Map.of("branchIds", List.of(1))), List.of())).isNull();
    String companyOnly = "โปรที่จำกัดสาขาสร้าง / แก้ได้เฉพาะผู้ดูแลระบบระดับบริษัท";
    assertThat(PromotionRules.promotionBranchScopeError(List.of(1L), null, false)).isEqualTo(companyOnly);
    assertThat(PromotionRules.promotionBranchScopeError(List.of(), List.of(1L), false)).isEqualTo(companyOnly);
    assertThat(PromotionRules.promotionBranchScopeError(List.of(), List.of(), false)).isNull();
    assertThat(PromotionRules.promotionBranchScopeError(List.of(1L), null, true)).isNull();
  }
}
