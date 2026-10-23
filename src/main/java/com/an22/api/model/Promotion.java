package com.an22.api.model;

import java.util.List;

/**
 * {@code Promotion} / {@code PromotionPayload} (promotion.model.ts) — ตัวเลขเป็น Double ให้กฎ "จำนวนเต็ม" ของ {@code promotionError()}
 * ตรวจได้ (Jackson ตัดทศนิยมเงียบ ๆ ถ้าเป็น Integer); ขาเข้า field ที่ไม่ส่ง = null → ค่าเริ่ม ({@code PROMOTION_DEFAULTS}).
 */
public record Promotion(
    Long id,
    String code,
    String name,
    String note,
    String type,
    String startDate,
    String endDate,
    Boolean enabled,
    Double priority,
    Boolean stackable,
    Scope scope,
    Double minQty,
    Double minAmount,
    Discount discount,
    FreeGoods freeGoods,
    List<Long> branchIds) {

  /** {@code PromotionScope}: หมวดครอบคลุม SKU ใต้หมวดทั้งหมด; bill_discount ไม่ใช้. */
  public record Scope(Boolean all, List<Long> productIds, List<Long> categoryIds) {}

  /** {@code PromotionDiscount}: kind percent / amount, maxDiscount ใช้กับ percent เท่านั้น. */
  public record Discount(String kind, Double value, Double maxDiscount) {}

  /** {@code PromotionFreeGoods}: ของแถมต่อชุด, repeat = ทุกเท่าของเงื่อนไขได้อีกชุด, maxSets null = ไม่จำกัด. */
  public record FreeGoods(List<FreeItem> items, Boolean repeat, Double maxSets) {}

  public record FreeItem(Long productId, Double qty) {}
}
