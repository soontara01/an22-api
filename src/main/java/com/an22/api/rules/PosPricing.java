package com.an22.api.rules;

import com.an22.api.model.CartItem;
import com.an22.api.model.Category;
import com.an22.api.model.FreeSerial;
import com.an22.api.model.ManualDiscount;
import com.an22.api.model.PackUnit;
import com.an22.api.model.Product;
import com.an22.api.model.Promotion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * port จาก {@code core/models/pos-pricing.model.ts} — กฎเดียวของยอดตะกร้า (POS + server คิดใหม่ทุกบิล): ราคาตั้ง × factor → โปรสินค้า
 * (priority / stackable) → ของแถม → โปรท้ายบิล → เฉลี่ยส่วนลดท้ายบิลลงบรรทัด → ส่วนลดพิเศษ → VAT ต่อบรรทัด. ผลต้องตรง
 * {@code docs/backend/fixtures/price-cart.json} ทุกตัวเลข / ข้อความ (FixturesTest) — แก้ลำดับการคิด / การปัดไม่ได้.
 */
public final class PosPricing {

  /** ท้ายข้อความเตือนเมื่อส่วนลดพิเศษทำให้บรรทัดต่ำกว่าทุน (ไม่บล็อก). */
  public static final String BELOW_COST_WARNING = "ขายต่ำกว่าทุนหลังส่วนลดพิเศษ";

  private PosPricing() {}

  /**
   * {@code PricingContext}: products มี currentPrice / stock / avgCost ของสาขาที่ขาย; date = วันขาย 'YYYY-MM-DD';
   * vatRegistered false → VAT 0 ทุกบรรทัด (B20); branchId = สาขาของบิล — โปรที่จำกัดสาขาอื่นไม่คิด (D2).
   */
  public record PricingContext(
      List<Product> products, List<Promotion> promotions, List<Category> categories, String date, boolean vatRegistered, Long branchId) {}

  /** {@code PricedLine} = {@code SaleLine} ไม่มี cogs + cartIndex (null = บรรทัดของแถม). */
  public record PricedLine(
      long productId,
      String sku,
      String name,
      String shortName,
      String unit,
      double factor,
      String vatType,
      String itemType,
      int warrantyMonths,
      double qty,
      double unitPrice,
      double listPrice,
      double itemDiscount,
      double billDiscount,
      double manualDiscount,
      double amount,
      double vat,
      List<Long> promotionIds,
      Long freeOfPromotionId,
      String serial,
      Integer cartIndex) {}

  /** {@code CartIssue}: blocking = ห้ามปิดบิล, อื่น ๆ = เตือน. */
  public record CartIssue(Integer cartIndex, long productId, String message, boolean blocking) {}

  /** {@code PricedCart}: lines = บรรทัดจ่ายเงินตามตะกร้า แล้วบรรทัดของแถม. */
  public record PricedCart(
      List<PricedLine> lines,
      double subtotal,
      double itemDiscount,
      double billDiscount,
      double manualDiscount,
      double manualBillDiscount,
      double total,
      double vat,
      List<Long> billPromotionIds,
      double itemCount,
      List<CartIssue> issues) {}

  /** {@code belowCostIssues()}: คำเตือนต่ำกว่าทุน (คำตอบของ cost-check สำหรับคนที่ไม่เห็นทุน — E45). */
  public static List<CartIssue> belowCostIssues(List<CartIssue> issues) {
    return issues.stream().filter(i -> !i.blocking() && i.message().endsWith(BELOW_COST_WARNING)).toList();
  }

  /** {@code freeSets()}: จำนวนชุดของแถมจาก {@code qty} หน่วยฐาน / {@code amount} บาทของสินค้าร่วมรายการ. */
  public static int freeSets(Promotion p, double qty, double amount) {
    Promotion.FreeGoods free = p.freeGoods();
    if (free == null) {
      return 0;
    }
    double sets = p.minQty() > 0 ? Math.floor(qty / p.minQty()) : p.minAmount() > 0 ? amount / p.minAmount() : 0;
    sets = Math.floor(sets + 1e-9);
    if (!Boolean.TRUE.equals(free.repeat())) {
      sets = Math.min(sets, 1);
    }
    if (free.maxSets() != null) {
      sets = Math.min(sets, free.maxSets());
    }
    return (int) Math.max(sets, 0);
  }

  /** {@code manualDiscountValueError()}: % ต้อง 0 < v ≤ 100, บาท > 0 ทศนิยมไม่เกิน 2 — null = ถูก. */
  public static String manualDiscountValueError(ManualDiscount discount) {
    double value = discount.value() == null ? Double.NaN : discount.value();
    if ("percent".equals(discount.kind())) {
      return value > 0 && value <= 100 ? null : "ส่วนลดพิเศษต้องมากกว่า 0 และไม่เกิน 100%";
    }
    return value > 0 && Money.round2(value) == value ? null : "จำนวนเงินส่วนลดพิเศษไม่ถูกต้อง";
  }

  /** {@code manualDiscountBaht()}: บาทของส่วนลดพิเศษที่ถูกต้องบน {@code base} (ไม่เกิน base). */
  public static double manualDiscountBaht(ManualDiscount discount, double base) {
    double baht = "percent".equals(discount.kind()) ? Money.round2((base * discount.value()) / 100) : discount.value();
    return Math.min(baht, base);
  }

  /**
   * {@code manualDiscountError()}: มีส่วนลดพิเศษต้องมีเหตุผลเสมอ; พนักงานให้ได้ไม่เกิน {@code maxPercent} ของราคาหลังโปรต่อบรรทัด
   * (ของบรรทัด + ส่วนแบ่งท้ายบิล) เกินนั้นเฉพาะ admin.
   */
  public static String manualDiscountError(PricedCart cart, String reason, boolean isAdmin, double maxPercent) {
    if (cart.manualDiscount() <= 0) {
      return null;
    }
    if (reason == null || reason.isBlank()) {
      return "กรุณาระบุเหตุผลส่วนลดพิเศษ";
    }
    if (isAdmin) {
      return null;
    }
    return cart.lines().stream()
        .filter(l -> l.manualDiscount() > Money.round2(((l.amount() + l.manualDiscount()) * maxPercent) / 100))
        .findFirst()
        .map(l -> "ส่วนลดพิเศษ " + l.sku() + " เกิน " + PriceRules.jsNumber(maxPercent) + "% — ให้ผู้ดูแลระบบทำรายการ")
        .orElse(null);
  }

  /** {@code cartError()}: ปัญหาแรกที่บล็อก หรือตะกร้าว่าง — null = ปิดบิลได้. */
  public static String cartError(PricedCart cart) {
    return cart.issues().stream().filter(CartIssue::blocking).map(CartIssue::message).findFirst()
        .orElse(cart.lines().isEmpty() ? "ยังไม่มีสินค้าในบิล" : null);
  }

  /** บรรทัดจ่ายเงินระหว่างคิด ({@code Working} ของ TS). */
  private static final class Working {
    final int index;
    final CartItem item;
    final Product product;
    final double baseQty;
    final double gross;
    List<Promotion> promos = List.of();
    double itemDiscount;

    Working(int index, CartItem item, Product product, double baseQty, double gross) {
      this.index = index;
      this.item = item;
      this.product = product;
      this.baseQty = baseQty;
      this.gross = gross;
    }
  }

  /** {@code priceCart()}: ไม่ throw — บรรทัดที่ขายไม่ได้ถูกตัดออกและรายงานใน issues. */
  public static PricedCart priceCart(List<CartItem> items, PricingContext ctx, List<FreeSerial> freeSerials, ManualDiscount billManual) {
    List<CartIssue> issues = new ArrayList<>();
    Map<Long, Product> products = new HashMap<>();
    for (Product p : ctx.products()) {
      products.put(p.id(), p);
    }
    List<Promotion> active = ctx.promotions().stream()
        .filter(p -> "active".equals(PromotionRules.promotionStatus(p.enabled(), p.startDate(), p.endDate(), ctx.date()))
            && PromotionRules.inBranch(p.branchIds(), ctx.branchId()))
        .sorted(BY_PRIORITY)
        .toList();
    List<Promotion> itemPromos = active.stream().filter(p -> !"bill_discount".equals(p.type())).toList();
    Blocking blocking = (index, productId, message) -> issues.add(new CartIssue(index, productId, message, true));

    // 1. บรรทัดจ่ายเงินที่ถูกต้องที่ราคาตั้ง
    List<Working> lines = new ArrayList<>();
    Set<String> seenSerials = new HashSet<>();
    for (int index = 0; index < items.size(); index++) {
      CartItem item = items.get(index);
      Product product = products.get(item.productId());
      if (product == null) {
        blocking.add(index, item.productId(), "ไม่พบสินค้า #" + item.productId());
        continue;
      }
      if (!ProductRules.canSell(product.saleStatus())) {
        blocking.add(index, product.id(), product.sku() + " ห้ามขาย");
        continue;
      }
      if (product.currentPrice() == null) {
        blocking.add(index, product.id(), product.sku() + " ยังไม่กำหนดราคาขาย");
        continue;
      }
      double factor = item.factor() == null ? Double.NaN : item.factor();
      if (factor != 1 && product.packUnits().stream().noneMatch(u -> u.factor() == factor)) {
        blocking.add(index, product.id(), product.sku() + " ไม่มีหน่วยขายนี้");
        continue;
      }
      double qty = item.qty() == null ? Double.NaN : item.qty();
      if (!isInteger(qty) || qty < 1) {
        blocking.add(index, product.id(), product.sku() + " จำนวนต้องเป็นจำนวนเต็ม ≥ 1");
        continue;
      }
      if (product.serialControl()) {
        if (qty != 1) {
          blocking.add(index, product.id(), product.sku() + " ขายทีละ 1 ซีเรียล");
          continue;
        }
        if (item.serial() == null || item.serial().isEmpty()) {
          blocking.add(index, product.id(), product.sku() + " ต้องระบุซีเรียล");
        } else if (!seenSerials.add(item.serial())) {
          blocking.add(index, product.id(), "ซีเรียล " + item.serial() + " ซ้ำในบิล");
        }
      }
      lines.add(new Working(index, item, product, qty * factor, Money.round2(product.currentPrice() * factor * qty)));
    }

    // 2. โปรสินค้าต่อบรรทัด
    Map<Long, Boolean> reachable = new HashMap<>();
    for (Promotion p : itemPromos) {
      if (!"free_goods".equals(p.type())) {
        continue;
      }
      List<Working> scoped = lines.stream().filter(l -> inScope(p, l.product, ctx)).toList();
      double qty = 0;
      for (Working l : scoped) {
        qty += l.baseQty;
      }
      double amount = sum(scoped.stream().map(l -> l.gross).toList());
      reachable.put(p.id(), freeSets(p, qty, amount) > 0);
    }
    for (Working line : lines) {
      List<Promotion> candidates = itemPromos.stream().filter(p -> inScope(p, line.product, ctx)).toList();
      line.promos = selectPromotions(candidates, p -> "item_discount".equals(p.type())
          ? line.baseQty >= p.minQty()
          : Boolean.TRUE.equals(reachable.get(p.id())));
      double unitPrice = line.product.currentPrice();
      for (Promotion p : line.promos) {
        if ("item_discount".equals(p.type()) && p.discount() != null) {
          unitPrice = Money.round2(unitPrice - PromotionRules.discountAmount(unitPrice, p.discount()));
        }
      }
      double perUnit = Money.round2(line.product.currentPrice() - unitPrice);
      line.itemDiscount = Math.min(Money.round2(perUnit * line.baseQty), line.gross);
    }

    // สต็อกที่เหลือต่อ SKU หลังบรรทัดจ่ายเงิน (บริการไม่จำกัด) — ลำดับตามที่พบครั้งแรก (Map ของ JS)
    Map<Long, Double> stockLeft = new LinkedHashMap<>();
    for (Working line : lines) {
      if (ProductRules.isService(line.product.itemType())) {
        continue;
      }
      double left = stockLeft.getOrDefault(line.product.id(), (double) line.product.stock()) - line.baseQty;
      stockLeft.put(line.product.id(), left);
    }
    stockLeft.forEach((productId, left) -> {
      if (left < 0) {
        Product product = products.get(productId);
        blocking.add(null, productId, product.sku() + " สต็อกไม่พอ (คงเหลือ " + product.stock() + " " + product.unit() + ")");
      }
    });

    // 3. ของแถม
    List<PricedLine> freeLines = new ArrayList<>();
    List<FreeSerial> freeSerialsLeft = new ArrayList<>(freeSerials);
    for (Promotion p : itemPromos) {
      if (!"free_goods".equals(p.type())) {
        continue;
      }
      List<Working> chosen = lines.stream().filter(l -> l.promos.contains(p)).toList();
      double qty = 0;
      for (Working l : chosen) {
        qty += l.baseQty;
      }
      double amount = sum(chosen.stream().map(l -> l.gross - l.itemDiscount).toList());
      int sets = freeSets(p, qty, amount);
      if (sets == 0) {
        continue;
      }
      for (Promotion.FreeItem free : p.freeGoods().items()) {
        Product product = products.get(free.productId());
        if (product == null || !ProductRules.canSell(product.saleStatus())) {
          continue;
        }
        double freeQty = free.qty() * sets;
        if (!ProductRules.isService(product.itemType())) {
          double left = Math.max(stockLeft.getOrDefault(product.id(), (double) product.stock()), 0);
          if (left < freeQty) {
            issues.add(new CartIssue(null, product.id(), "ของแถม " + product.sku() + " สต็อกไม่พอ แถมได้ " + PriceRules.jsNumber(left)
                + " จาก " + PriceRules.jsNumber(freeQty) + " " + product.unit(), false));
            freeQty = left;
          }
          stockLeft.put(product.id(), left - freeQty);
        }
        if (freeQty == 0) {
          continue;
        }
        double listPrice = product.currentPrice() == null ? 0 : product.currentPrice();
        if (!product.serialControl()) {
          freeLines.add(freeLine(product, freeQty, listPrice, p.id(), null));
          continue;
        }
        for (int n = 0; n < freeQty; n++) {
          String serial = null;
          for (int at = 0; at < freeSerialsLeft.size(); at++) {
            FreeSerial s = freeSerialsLeft.get(at);
            if (Objects.equals(s.promotionId(), p.id()) && Objects.equals(s.productId(), product.id())) {
              serial = freeSerialsLeft.remove(at).serial();
              break;
            }
          }
          if (serial == null || serial.isEmpty()) {
            blocking.add(null, product.id(), "ต้องสแกนซีเรียลของแถม " + product.sku());
          } else if (!seenSerials.add(serial)) {
            blocking.add(null, product.id(), "ซีเรียล " + serial + " ซ้ำในบิล");
          }
          freeLines.add(freeLine(product, 1, listPrice, p.id(), serial));
        }
      }
    }

    // 4. โปรท้ายบิลบนยอดหลังโปรสินค้า
    double afterItems = sum(lines.stream().map(l -> l.gross - l.itemDiscount).toList());
    List<Promotion> billPromos = selectPromotions(
        active.stream().filter(p -> "bill_discount".equals(p.type())).toList(),
        p -> afterItems > 0 && afterItems >= p.minAmount());
    double running = afterItems;
    for (Promotion p : billPromos) {
      if (p.discount() != null) {
        running = Money.round2(running - PromotionRules.discountAmount(running, p.discount()));
      }
    }
    double billDiscount = Money.round2(afterItems - running);

    // 5. เฉลี่ยส่วนลดท้ายบิลลงบรรทัดจ่ายเงินตามสัดส่วน
    double[] shares = allocate(billDiscount, lines.stream().mapToDouble(l -> l.gross - l.itemDiscount).toArray());
    double[] afterPromos = new double[lines.size()];
    for (int i = 0; i < lines.size(); i++) {
      afterPromos[i] = Money.round2(lines.get(i).gross - lines.get(i).itemDiscount - shares[i]);
    }

    // 6. ส่วนลดพิเศษหลังโปรทั้งหมด: ของบรรทัดก่อน แล้วท้ายบิลเฉลี่ยตามสัดส่วน — ไม่ตรวจสิทธิ์โปรใหม่
    double[] own = new double[lines.size()];
    double[] rest = new double[lines.size()];
    for (int i = 0; i < lines.size(); i++) {
      Working l = lines.get(i);
      own[i] = manualAmount(l.item.manualDiscount(), afterPromos[i], l.product.sku(),
          message -> blocking.add(l.index, l.product.id(), message));
      rest[i] = Money.round2(afterPromos[i] - own[i]);
    }
    double manualBillDiscount = manualAmount(billManual, sum(rest), "ส่วนลดท้ายบิล", message -> blocking.add(null, 0, message));
    double[] manualShares = allocate(manualBillDiscount, rest);
    double[] manual = new double[lines.size()];
    for (int i = 0; i < lines.size(); i++) {
      manual[i] = Money.round2(own[i] + manualShares[i]);
    }

    List<PricedLine> paid = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      Working l = lines.get(i);
      Product product = l.product;
      double amount = Money.round2(afterPromos[i] - manual[i]);
      if (manual[i] > 0 && !ProductRules.isService(product.itemType())) {
        double cost = Money.round2(Costing.effectiveCost(product.stock(), product.avgCost(), product.cost()) * l.baseQty);
        double net = ctx.vatRegistered() ? ProductRules.vatBreakdown(amount, product.vatType())[0] : amount;
        if (net < cost) {
          issues.add(new CartIssue(l.index, product.id(), product.sku() + " " + BELOW_COST_WARNING, false));
        }
      }
      double price = Money.round2(product.currentPrice() * l.item.factor());
      paid.add(new PricedLine(product.id(), product.sku(), product.name(), shortName(product), unit(product, l.item.factor()),
          l.item.factor(), product.vatType(), product.itemType(), product.warrantyMonths(), l.item.qty(), price, price, l.itemDiscount,
          shares[i], manual[i], amount, ctx.vatRegistered() ? ProductRules.vatBreakdown(amount, product.vatType())[1] : 0,
          l.promos.stream().map(Promotion::id).toList(), null, product.serialControl() ? l.item.serial() : null, l.index));
    }
    List<PricedLine> all = new ArrayList<>(paid);
    all.addAll(freeLines);
    double itemCount = 0;
    for (PricedLine l : all) {
      itemCount += l.qty() * l.factor();
    }
    return new PricedCart(
        List.copyOf(all),
        sum(lines.stream().map(l -> l.gross).toList()),
        sum(lines.stream().map(l -> l.itemDiscount).toList()),
        billDiscount,
        sum(manual),
        manualBillDiscount,
        sum(paid.stream().map(PricedLine::amount).toList()),
        sum(paid.stream().map(PricedLine::vat).toList()),
        billDiscount > 0 ? billPromos.stream().map(Promotion::id).toList() : List.of(),
        itemCount,
        List.copyOf(issues));
  }

  /** ลำดับตายตัว: priority สูงก่อน แล้ว id. */
  private static final Comparator<Promotion> BY_PRIORITY =
      Comparator.comparing(Promotion::priority, Comparator.reverseOrder()).thenComparing(Promotion::id);

  private interface Blocking {
    void add(Integer index, long productId, String message);
  }

  /** {@code selectPromotions()}: ตัวแรกที่เข้าเงื่อนไขใช้เสมอ (ไม่ stackable = หยุด), ตัวต่อไปเฉพาะที่ stackable. */
  private static List<Promotion> selectPromotions(List<Promotion> candidates, Predicate<Promotion> eligible) {
    List<Promotion> applied = new ArrayList<>();
    for (Promotion p : candidates) {
      if (!eligible.test(p)) {
        continue;
      }
      if (applied.isEmpty()) {
        applied.add(p);
        if (!Boolean.TRUE.equals(p.stackable())) {
          break;
        }
      } else if (Boolean.TRUE.equals(p.stackable())) {
        applied.add(p);
      }
    }
    return applied;
  }

  private static boolean inScope(Promotion p, Product product, PricingContext ctx) {
    return PromotionRules.inScope(p, product.id(), product.categoryId(), ctx.categories());
  }

  /** {@code allocate()}: แบ่ง total ตามน้ำหนัก (สตางค์) เศษไปบรรทัดที่น้ำหนักมากสุด (ตัวแรก). */
  private static double[] allocate(double total, double[] weights) {
    double whole = sum(weights);
    double[] shares = new double[weights.length];
    for (int i = 0; i < weights.length; i++) {
      shares[i] = whole > 0 ? Money.round2((total * weights[i]) / whole) : 0;
    }
    double remainder = Money.round2(total - sum(shares));
    if (remainder != 0 && weights.length > 0) {
      int largest = 0;
      for (int i = 1; i < weights.length; i++) {
        if (weights[i] > weights[largest]) {
          largest = i;
        }
      }
      shares[largest] = Money.round2(shares[largest] + remainder);
    }
    return shares;
  }

  /** บาทของส่วนลดพิเศษบน base — ค่าผิดรายงานผ่าน report แล้วได้ 0. */
  private static double manualAmount(ManualDiscount discount, double base, String label, Consumer<String> report) {
    if (discount == null) {
      return 0;
    }
    String problem = manualDiscountValueError(discount);
    if (problem != null) {
      report.accept(label + ": " + problem);
      return 0;
    }
    return manualDiscountBaht(discount, base);
  }

  private static PricedLine freeLine(Product product, double qty, double listPrice, long promotionId, String serial) {
    return new PricedLine(product.id(), product.sku(), product.name(), shortName(product), unit(product, 1), 1, product.vatType(),
        product.itemType(), product.warrantyMonths(), qty, 0, listPrice, 0, 0, 0, 0, 0, List.of(promotionId), promotionId, serial, null);
  }

  private static String shortName(Product product) {
    return product.shortName() == null || product.shortName().isEmpty() ? product.name() : product.shortName();
  }

  /** หน่วยขาย: หน่วยแพ็กที่ตัวคูณตรง ไม่งั้นหน่วยฐาน. */
  private static String unit(Product product, double factor) {
    return product.packUnits().stream().filter(u -> u.factor() == factor).map(PackUnit::unit).findFirst().orElse(product.unit());
  }

  /** ผลรวมปัดสตางค์ ({@code sum()} ของ TS: บวกจากซ้ายแล้วปัดครั้งเดียว). */
  private static double sum(List<Double> values) {
    double total = 0;
    for (double v : values) {
      total += v;
    }
    return Money.round2(total);
  }

  private static double sum(double[] values) {
    double total = 0;
    for (double v : values) {
      total += v;
    }
    return Money.round2(total);
  }

  /** {@code Number.isInteger()}. */
  private static boolean isInteger(double n) {
    return Double.isFinite(n) && n == Math.rint(n);
  }
}
