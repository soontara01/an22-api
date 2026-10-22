package com.an22.api.rules;

import com.an22.api.model.Category;
import com.an22.api.model.PackUnit;
import com.an22.api.model.Product;
import com.an22.api.model.SkuSupplier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** port จาก {@code core/models/product.model.ts} + กฎ SKU ที่อยู่ใน mock ({@code validateSku()}, {@code validateSkuSuppliers()}, …). */
public final class ProductRules {

  public static final int SHORT_NAME_MAX = 20;
  public static final double VAT_RATE = 0.07;
  public static final Map<String, String> SKU_STATUS_LABEL =
      Map.of("active", "ขายได้", "no_sale", "ห้ามขาย", "no_purchase", "ห้ามสั่งซื้อ", "discontinued", "เลิกจำหน่าย");

  private ProductRules() {}

  public static boolean isService(String itemType) {
    return "service".equals(itemType);
  }

  public static boolean canSell(String saleStatus) {
    return "active".equals(saleStatus) || "no_purchase".equals(saleStatus);
  }

  public static boolean canPurchase(String saleStatus) {
    return "active".equals(saleStatus) || "no_sale".equals(saleStatus);
  }

  public static boolean isDiscontinued(String saleStatus) {
    return "discontinued".equals(saleStatus);
  }

  /** {@code vatBreakdown()}: ราคารวม VAT → [ก่อน VAT, VAT] ปัดสตางค์. */
  public static double[] vatBreakdown(double priceInclVat, String vatType) {
    if ("exempt".equals(vatType)) {
      return new double[] {priceInclVat, 0};
    }
    double net = Money.round2(priceInclVat / (1 + VAT_RATE));
    return new double[] {net, Money.round2(priceInclVat - net)};
  }

  /** {@code allBarcodes()}: บาร์โค้ดที่ไม่ว่างทั้งหมด (ฐาน + แพ็ก). */
  public static List<String> allBarcodes(String barcode, List<PackUnit> packs) {
    List<String> codes = new ArrayList<>();
    if (barcode != null && !barcode.isEmpty()) {
      codes.add(barcode);
    }
    for (PackUnit p : packs) {
      if (p.barcode() != null && !p.barcode().isEmpty()) {
        codes.add(p.barcode());
      }
    }
    return codes;
  }

  /** {@code withServiceRules()}: SKU บริการไม่มี serial / แพ็ก / จุดสั่งซื้อ. */
  public static Product withServiceRules(Product p) {
    if (!isService(p.itemType())) {
      return p;
    }
    return new Product(p.id(), p.sku(), p.name(), p.shortName(), p.categoryId(), p.categoryPath(), p.unit(), p.barcode(), List.of(),
        p.brand(), p.model(), p.itemType(), p.imageUrl(), p.vatType(), p.cost(), p.avgCost(), p.currentPrice(), p.stock(), 0, 0,
        p.suppliers(), p.warrantyMonths(), false, "", null, p.saleStatus());
  }

  /** ข้อมูล SKU อื่นที่กฎต้องใช้ (รหัส + บาร์โค้ดทั้งหมด). */
  public record Other(String sku, List<String> barcodes) {}

  /**
   * {@code validateSku()} + {@code validateSkuSuppliers()} ของ mock — payload ผ่าน {@link #withServiceRules} แล้ว, {@code others} = SKU อื่น
   * ของบริษัท, {@code supplierIds} = ผู้จำหน่ายที่มีอยู่.
   */
  public static String skuError(Product p, List<Other> others, List<Category> categories, Set<Long> supplierIds) {
    if (!"stock".equals(p.itemType()) && !"service".equals(p.itemType())) {
      return "ประเภทสินค้าไม่ถูกต้อง";
    }
    if (others.stream().anyMatch(o -> o.sku().toUpperCase(Locale.ROOT).equals(p.sku().toUpperCase(Locale.ROOT)))) {
      return "รหัส SKU " + p.sku() + " ซ้ำ";
    }
    Category category = categories.stream().filter(c -> p.categoryId() != null && c.id() == p.categoryId()).findFirst().orElse(null);
    if (category == null) {
      return "กรุณาเลือกหมวดหมู่";
    }
    if (!category.active() || !CategoryRules.isLeaf(categories, category.id())) {
      return "ต้องเลือกหมวดหมู่ย่อยสุดที่เปิดใช้งาน";
    }
    if (p.shortName().length() > SHORT_NAME_MAX) {
      return "ชื่อย่อต้องไม่เกิน " + SHORT_NAME_MAX + " ตัวอักษร";
    }
    List<PackUnit> packs = p.packUnits();
    if (p.serialControl() && !packs.isEmpty()) {
      return "สินค้าคุม Serial มีได้หน่วยเดียว";
    }
    if (packs.stream().anyMatch(u -> u.factor() == null || u.factor() <= 1)) {
      return "ตัวคูณของหน่วยต้องเป็นจำนวนเต็มมากกว่า 1";
    }
    List<String> units = packs.stream().map(u -> u.unit() == null ? "" : u.unit().strip()).toList();
    for (int i = 0; i < units.size(); i++) {
      String u = units.get(i);
      if (u.isEmpty() || u.equals(p.unit()) || units.indexOf(u) != i) {
        return "ชื่อหน่วยต้องไม่ว่าง ไม่ซ้ำกัน และไม่ซ้ำหน่วยฐาน";
      }
    }
    List<String> own = allBarcodes(p.barcode(), packs);
    Set<String> taken = new LinkedHashSet<>();
    for (int i = 0; i < own.size(); i++) {
      String code = own.get(i);
      if (own.indexOf(code) != i || others.stream().anyMatch(o -> o.barcodes().contains(code))) {
        taken.add(code);
      }
    }
    if (!taken.isEmpty()) {
      return "บาร์โค้ดซ้ำ: " + String.join(", ", taken);
    }
    return supplierError(p, supplierIds);
  }

  /** {@code validateSkuSuppliers()}. */
  static String supplierError(Product p, Set<Long> supplierIds) {
    if (p.maxStock() > 0 && p.maxStock() < p.minStock()) {
      return "สต็อกสูงสุดต้องไม่น้อยกว่าจุดสั่งซื้อ";
    }
    List<SkuSupplier> links = p.suppliers();
    if (links.isEmpty()) {
      return null;
    }
    List<Long> ids = links.stream().map(SkuSupplier::supplierId).toList();
    if (ids.stream().distinct().count() != ids.size()) {
      return "ผู้จำหน่ายซ้ำกันใน SKU นี้";
    }
    if (links.stream().filter(l -> Boolean.TRUE.equals(l.isMain())).count() != 1) {
      return "ต้องเลือกผู้จำหน่ายหลัก 1 ราย";
    }
    if (ids.stream().anyMatch(id -> id == null || !supplierIds.contains(id))) {
      return "ไม่พบผู้จำหน่าย";
    }
    if (links.stream().anyMatch(l -> l.cost() < 0 || l.leadTimeDays() < 0 || l.moq() < 0)) {
      return "ราคาซื้อ / lead time / MOQ ต้องไม่ติดลบ";
    }
    return null;
  }

  /** {@code receiptCostError()} ของ mock: ทุนรับเข้าต้องมีและไม่ติดลบ (ทศนิยมเกิน 2 ได้ — ทุนแพ็ก ÷ ตัวคูณ). */
  public static String receiptCostError(Double cost) {
    String problem = Money.moneyProblem(cost);
    if (problem == null || "decimals".equals(problem)) {
      return null;
    }
    return "required".equals(problem) ? "กรุณากรอกต้นทุน" : "ต้นทุนต้องไม่ติดลบ";
  }

  /** {@code purchaseBlocked()} ของ mock. */
  public static String purchaseError(String saleStatus) {
    return canPurchase(saleStatus) ? null : "สถานะ \"" + SKU_STATUS_LABEL.get(saleStatus) + "\" ห้ามรับเข้าสินค้า";
  }
}
