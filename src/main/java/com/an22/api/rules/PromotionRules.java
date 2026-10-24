package com.an22.api.rules;

import com.an22.api.model.Category;
import com.an22.api.model.Promotion;
import com.an22.api.model.Promotion.Discount;
import com.an22.api.model.Promotion.FreeGoods;
import com.an22.api.model.Promotion.FreeItem;
import com.an22.api.model.Promotion.Scope;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * port จาก {@code core/models/promotion.model.ts} (ส่วน master: สถานะ / สาขา / {@code promotionError()}) + {@code normalize} ของ
 * {@code handlePromotions} ใน mock + {@code discountAmount()} / {@code inScope()} ที่ {@link PosPricing} ใช้.
 */
public final class PromotionRules {

  public static final String BRANCH_SCOPE_ADMIN = "โปรที่จำกัดสาขาสร้าง / แก้ได้เฉพาะผู้ดูแลระบบระดับบริษัท";

  private static final Scope DEFAULT_SCOPE = new Scope(false, List.of(), List.of());

  private PromotionRules() {}

  /** SKU ในบริษัท (เท่าที่กฎใช้). */
  public record ProductRef(long id, String sku, String saleStatus) {}

  public record CategoryRef(long id, String name, boolean active) {}

  public record BranchRef(long id, boolean active) {}

  /** {@code PromotionContext}: {@code promotions} = ทุกโปรที่เก็บ (เฉพาะ id + code สำหรับกฎ "ไม่ซ้ำ"). */
  public record Context(
      List<ProductRef> products, List<CategoryRef> categories, List<Promotion> promotions, List<BranchRef> branches, String today) {}

  /**
   * {@code normalize} ของ mock: ค่าเริ่ม {@code PROMOTION_DEFAULTS} แทน field ที่ไม่ส่ง, code trim + ตัวใหญ่, name trim, endDate '' → null,
   * bill_discount ใช้ทั้งบิล (scope ทั้งหมด). discount ไม่เติมค่าเริ่ม (Java แยก "ไม่ส่ง" กับ null ไม่ได้ — E2E B4): ไม่ส่ง / null = ไม่มีส่วนลด
   * → โปรส่วนลดได้ {@code กรุณากำหนดส่วนลด} (mock: null → ข้อความนี้, ไม่ส่ง → 10% ของ {@code PROMOTION_DEFAULTS}; หน้าบ้านส่ง discount เสมอ).
   */
  public static Promotion normalize(Promotion p) {
    String type = p.type() != null ? p.type() : "item_discount";
    Scope scope = "bill_discount".equals(type)
        ? new Scope(true, List.of(), List.of())
        : p.scope() == null ? DEFAULT_SCOPE : new Scope(Boolean.TRUE.equals(p.scope().all()), orEmpty(p.scope().productIds()),
            orEmpty(p.scope().categoryIds()));
    FreeGoods free = p.freeGoods() == null ? null
        : new FreeGoods(orEmpty(p.freeGoods().items()), Boolean.TRUE.equals(p.freeGoods().repeat()), p.freeGoods().maxSets());
    return new Promotion(null, p.code() == null ? "" : p.code().strip().toUpperCase(Locale.ROOT), p.name() == null ? "" : p.name().strip(),
        p.note() == null ? "" : p.note(), type, p.startDate(), p.endDate() == null || p.endDate().isEmpty() ? null : p.endDate(),
        p.enabled() == null || p.enabled(), p.priority() != null ? p.priority() : 0.0, Boolean.TRUE.equals(p.stackable()), scope,
        p.minQty() != null ? p.minQty() : 1.0, p.minAmount() != null ? p.minAmount() : 0.0, p.discount(), free, orEmpty(p.branchIds()));
  }

  /** {@code discountAmount()}: บาทที่ลดจาก {@code base} (ราคาต่อหน่วยของโปรสินค้า / ยอดบิลของโปรท้ายบิล) ไม่เกิน base. */
  public static double discountAmount(double base, Discount d) {
    if (base <= 0) {
      return 0;
    }
    double raw = "percent".equals(d.kind()) ? (base * d.value()) / 100 : d.value();
    double capped = "percent".equals(d.kind()) && d.maxDiscount() != null ? Math.min(raw, d.maxDiscount()) : raw;
    return Money.round2(Math.min(capped, base));
  }

  /** {@code inScope()}: SKU เป็นสินค้าร่วมรายการของโปร — หมวดครอบคลุมหมวดลูกทั้งหมด (ไล่ขึ้นหาแม่). */
  public static boolean inScope(Promotion promo, long productId, Long categoryId, List<Category> categories) {
    if ("bill_discount".equals(promo.type()) || Boolean.TRUE.equals(promo.scope().all())) {
      return true;
    }
    if (promo.scope().productIds().contains(productId)) {
      return true;
    }
    Map<Long, Category> byId = new HashMap<>();
    for (Category c : categories) {
      byId.put(c.id(), c);
    }
    for (Category c = categoryId == null ? null : byId.get(categoryId); c != null; c = c.parentId() == null ? null : byId.get(c.parentId())) {
      if (promo.scope().categoryIds().contains(c.id())) {
        return true;
      }
    }
    return false;
  }

  /** {@code inBranch()}: ไม่จำกัดสาขา = ทุกสาขา; จำกัดแล้วใช้เฉพาะสาขาในรายการ (สาขาไม่รู้ = ไม่ใช้). */
  public static boolean inBranch(List<Long> branchIds, Long branchId) {
    return branchIds == null || branchIds.isEmpty() || (branchId != null && branchIds.contains(branchId));
  }

  /** {@code promotionStatus()}: active / scheduled / expired / disabled. */
  public static String promotionStatus(boolean enabled, String startDate, String endDate, String today) {
    return enabled ? PriceRules.priceStatus(new PriceRules.Range(startDate, endDate), today) : "disabled";
  }

  /** {@code hasStarted()}: โปรที่เริ่มแล้วถูกล็อก (การขายอาจอ้างถึง). */
  public static boolean hasStarted(String startDate, String today) {
    return startDate.compareTo(today) <= 0;
  }

  /** {@code promotionBranchScopeError()} (D2 / E6): โปรที่จำกัดสาขา (ก่อนหรือหลังแก้) เป็นของ admin บริษัท → 403; null = ทำได้. */
  public static String promotionBranchScopeError(List<Long> branchIds, List<Long> existingBranchIds, boolean isCompanyAdmin) {
    if (isCompanyAdmin) {
      return null;
    }
    return limited(branchIds) || limited(existingBranchIds) ? BRANCH_SCOPE_ADMIN : null;
  }

  /**
   * {@code promotionError()} ของโปรที่ {@link #normalize} แล้ว — null = ผ่าน. {@code existing} = ตัวที่เก็บไว้ตอนแก้: เริ่มแล้วแก้ได้เฉพาะ
   * enabled / endDate (เลื่อนให้เร็วขึ้น ไม่ก่อนวันนี้) / note.
   */
  public static String promotionError(Promotion p, Context ctx, Promotion existing) {
    if (!p.code().matches("[A-Z0-9-]{1,30}")) {
      return "รหัสโปรใช้ได้เฉพาะ A-Z, 0-9 และ - (ไม่เกิน 30 ตัว)";
    }
    Long existingId = existing == null ? null : existing.id();
    if (ctx.promotions().stream().anyMatch(x -> !Objects.equals(x.id(), existingId) && x.code().equals(p.code()))) {
      return "รหัสโปร " + p.code() + " ซ้ำ";
    }
    if (p.name().isBlank()) {
      return "กรุณากรอกชื่อโปรโมชั่น";
    }
    if (p.startDate() == null || PriceRules.parseDate(p.startDate()) == null
        || (p.endDate() != null && PriceRules.parseDate(p.endDate()) == null)) {
      return "รูปแบบวันที่ไม่ถูกต้อง";
    }
    if (p.endDate() != null && p.endDate().compareTo(p.startDate()) < 0) {
      return "วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม";
    }
    if (!isInteger(p.priority()) || p.priority() < 0 || p.priority() > 999) {
      return "ลำดับความสำคัญต้องเป็นจำนวนเต็ม 0 - 999";
    }

    if (existing != null && hasStarted(existing.startDate(), ctx.today())) {
      if (!lockedFields(existing).equals(lockedFields(p))) {
        return "โปรที่เริ่มแล้วแก้ได้เฉพาะวันสิ้นสุด (เลื่อนให้เร็วขึ้น) การเปิด/ปิดใช้งาน และหมายเหตุ";
      }
      if (!Objects.equals(p.endDate(), existing.endDate())) {
        if (p.endDate() == null) {
          return "โปรที่เริ่มแล้วขยายเป็นไม่กำหนดวันสิ้นสุดไม่ได้";
        }
        if (existing.endDate() != null && p.endDate().compareTo(existing.endDate()) > 0) {
          return "โปรที่เริ่มแล้วเลื่อนวันสิ้นสุดให้ช้าลงไม่ได้ (สร้างโปรใหม่แทน)";
        }
        if (p.endDate().compareTo(ctx.today()) < 0) {
          return "วันสิ้นสุดใหม่ต้องไม่ก่อนวันนี้ (ต้องการหยุดทันทีให้ปิดใช้งาน)";
        }
      }
      return null;
    }

    List<Long> branchIds = p.branchIds();
    if (new HashSet<>(branchIds).size() != branchIds.size()) {
      return "เลือกสาขาซ้ำกัน";
    }
    if (branchIds.stream().anyMatch(id -> ctx.branches().stream().noneMatch(b -> b.id() == id && b.active()))) {
      return "สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว";
    }

    if (!"bill_discount".equals(p.type())) {
      Scope scope = p.scope();
      if (!scope.all() && scope.productIds().isEmpty() && scope.categoryIds().isEmpty()) {
        return "กรุณาเลือกสินค้าหรือหมวดหมู่ที่ร่วมรายการ";
      }
      for (Long id : scope.productIds()) {
        ProductRef product = product(ctx, id);
        if (product == null) {
          return "ไม่พบ SKU #" + id;
        }
        if (ProductRules.isDiscontinued(product.saleStatus())) {
          return product.sku() + " เลิกจำหน่ายแล้ว";
        }
      }
      for (Long id : scope.categoryIds()) {
        CategoryRef category = ctx.categories().stream().filter(c -> id != null && c.id() == id).findFirst().orElse(null);
        if (category == null) {
          return "ไม่พบหมวดหมู่ #" + id;
        }
        if (!category.active()) {
          return "หมวดหมู่ " + category.name() + " ปิดใช้งาน";
        }
      }
    }
    if (!isCount(p.minQty()) || !(p.minAmount() >= 0)) {
      return "เงื่อนไขขั้นต่ำต้องไม่ติดลบ (จำนวนเป็นจำนวนเต็ม)";
    }

    if ("free_goods".equals(p.type())) {
      if (p.discount() != null) {
        return "โปรของแถมต้องไม่มีส่วนลด";
      }
      FreeGoods free = p.freeGoods();
      if (free == null || free.items().isEmpty()) {
        return "กรุณาเลือกสินค้าแถมอย่างน้อย 1 รายการ";
      }
      if (p.minQty() <= 0 && p.minAmount() <= 0) {
        return "กรุณากำหนดจำนวนหรือยอดซื้อขั้นต่ำ";
      }
      List<Long> ids = free.items().stream().map(FreeItem::productId).toList();
      if (new HashSet<>(ids).size() != ids.size()) {
        return "สินค้าแถมซ้ำกัน";
      }
      for (FreeItem item : free.items()) {
        ProductRef product = product(ctx, item.productId());
        if (product == null) {
          return "ไม่พบสินค้าแถม #" + item.productId();
        }
        if (!ProductRules.canSell(product.saleStatus())) {
          return product.sku() + " ขายไม่ได้ จึงเป็นของแถมไม่ได้";
        }
        if (!isInteger(item.qty()) || item.qty() < 1) {
          return "จำนวนของแถมต้องเป็นจำนวนเต็ม ≥ 1";
        }
      }
      if (free.maxSets() != null && (!isInteger(free.maxSets()) || free.maxSets() < 1)) {
        return "จำนวนชุดสูงสุดต่อบิลต้องเป็นจำนวนเต็ม ≥ 1";
      }
      return null;
    }

    if (p.freeGoods() != null) {
      return "โปรส่วนลดต้องไม่มีของแถม";
    }
    Discount d = p.discount();
    if (d == null) {
      return "กรุณากำหนดส่วนลด";
    }
    if ("percent".equals(d.kind()) && !(d.value() != null && d.value() > 0 && d.value() <= 100)) {
      return "ส่วนลด % ต้องมากกว่า 0 และไม่เกิน 100";
    }
    if ("amount".equals(d.kind()) && !(d.value() != null && d.value() > 0)) {
      return "ส่วนลดต้องมากกว่า 0 บาท";
    }
    if (d.maxDiscount() != null && (!"percent".equals(d.kind()) || !(d.maxDiscount() > 0))) {
      return "เพดานส่วนลดใช้กับส่วนลด % และต้องมากกว่า 0";
    }
    if ("item_discount".equals(p.type()) && p.minQty() < 1) {
      return "จำนวนขั้นต่ำต้องอย่างน้อย 1";
    }
    return null;
  }

  /**
   * field ที่ล็อกหลังเริ่ม (ทุกอย่างยกเว้น id / enabled / endDate / note) — record เทียบตามค่า ไม่สนลำดับ key แบบ {@code lockedFields()}.
   */
  private static Promotion lockedFields(Promotion p) {
    return new Promotion(null, p.code(), p.name(), null, p.type(), p.startDate(), null, null, p.priority(), p.stackable(), p.scope(),
        p.minQty(), p.minAmount(), p.discount(), p.freeGoods(), p.branchIds());
  }

  private static ProductRef product(Context ctx, Long id) {
    return ctx.products().stream().filter(x -> id != null && x.id() == id).findFirst().orElse(null);
  }

  private static boolean limited(List<Long> branchIds) {
    return branchIds != null && !branchIds.isEmpty();
  }

  /** {@code Number.isInteger()}. */
  private static boolean isInteger(Double n) {
    return n != null && Double.isFinite(n) && n == Math.rint(n);
  }

  private static boolean isCount(Double n) {
    return isInteger(n) && n >= 0;
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }
}
