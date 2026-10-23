package com.an22.api.product;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.config.ClockConfig;
import com.an22.api.db.TenantLocks;
import com.an22.api.masterdata.CategoryDao;
import com.an22.api.masterdata.SupplierDao;
import com.an22.api.model.Branch;
import com.an22.api.model.BranchStockRow;
import com.an22.api.model.Category;
import com.an22.api.model.Product;
import com.an22.api.model.SerialNumber;
import com.an22.api.model.SerialReceiveResult;
import com.an22.api.model.SerialReceiveResult.FromOtherBranch;
import com.an22.api.model.SkuSupplier;
import com.an22.api.model.StockCardResult;
import com.an22.api.model.Supplier;
import com.an22.api.price.PriceDao;
import com.an22.api.promotion.PromotionDao;
import com.an22.api.rules.CategoryRules;
import com.an22.api.rules.Costing;
import com.an22.api.rules.ProductRules;
import com.an22.api.rules.SerialRules;
import com.an22.api.stock.SerialDao;
import com.an22.api.stock.StockDao;
import com.an22.api.stock.StockLedger;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU + สต็อกต่อสาขา (D1) + serial — กฎตาม {@code handleProducts / handleSerials} ของ mock. ทุกการเปลี่ยนสต็อกผ่าน {@link StockLedger}.
 * สาขาของ request = X-Branch-Id (ไม่ส่ง = สาขาแรก สำหรับการอ่าน; งานเขียนต้องมี — 400).
 */
@Service
public class ProductService {

  static final String NOT_FOUND = "ไม่พบ SKU";
  static final String PRODUCTS = "products";
  static final Set<String> REMOVE_STATUSES = Set.of("sold", "damaged", "other");

  private final ProductDao dao;
  private final CategoryDao categories;
  private final SupplierDao suppliers;
  private final BranchDao branches;
  private final StockDao stock;
  private final SerialDao serials;
  private final StockLedger ledger;
  private final TenantLocks locks;
  private final Audit audit;
  private final PriceDao prices;
  private final PromotionDao promotions;
  private final Clock clock;

  ProductService(ProductDao dao, CategoryDao categories, SupplierDao suppliers, BranchDao branches, StockDao stock, SerialDao serials,
      StockLedger ledger, TenantLocks locks, Audit audit, PriceDao prices, PromotionDao promotions, Clock clock) {
    this.prices = prices;
    this.promotions = promotions;
    this.clock = clock;
    this.dao = dao;
    this.categories = categories;
    this.suppliers = suppliers;
    this.branches = branches;
    this.stock = stock;
    this.serials = serials;
    this.ledger = ledger;
    this.locks = locks;
    this.audit = audit;
  }

  /** {@code delta} เป็น Double เพื่อปฏิเสธเลขไม่เต็ม (1.5) แบบ mock แทนการตัดทศนิยมเงียบ ๆ. */
  public record StockAdjust(Double delta, Double unitCost, String note) {}

  public record SerialReceive(List<String> serials, Double unitCost, String note) {}

  public record SerialRemove(List<Long> ids, String status, String note) {}

  // ---------- SKU ----------

  @Transactional(readOnly = true)
  public List<Product> list(BranchContext ctx) {
    List<Category> cats = categories.list();
    Map<Long, Double> current = prices.current(today(), null);
    return dao.listAt(stockBranch(ctx)).stream().map(p -> derived(p, cats, current)).toList();
  }

  @Transactional(readOnly = true)
  public Product get(BranchContext ctx, long id) {
    return at(id, stockBranch(ctx));
  }

  /** {@code POST /products}: ไม่รับ stock / avgCost / currentPrice / categoryPath; min / max ลงสาขาใน header (E13). */
  @Transactional
  public Product create(ShopSession s, BranchContext ctx, Product in) {
    long branch = ctx.require();
    locks.lock(PRODUCTS);
    Product p = ProductRules.withServiceRules(withStoredCosts(s, merge(defaults(), in), null));
    badRequest(ProductRules.skuError(p, dao.others(null), categories.list(), supplierIds()));
    long id = dao.insert(p);
    dao.setLevels(branch, id, p.minStock(), p.maxStock());
    return at(id, branch);
  }

  /** {@code PUT /products/:id}: ทับเฉพาะที่ส่ง, สต็อกไม่เปลี่ยน; เปลี่ยนการคุม serial / ประเภทเมื่อสาขาใดยังมีของไม่ได้ (E18). */
  @Transactional
  public Product update(ShopSession s, BranchContext ctx, long id, Product in) {
    long branch = ctx.require();
    locks.lock(PRODUCTS);
    Product current = at(id, branch);
    Product p = ProductRules.withServiceRules(withStoredCosts(s, merge(current, in), current));
    badRequest(ProductRules.skuError(p, dao.others(id), categories.list(), supplierIds()));
    String stocked = stockedNote(id);
    if (!p.serialControl().equals(current.serialControl()) && !stocked.isEmpty()) {
      throw ApiException.badRequest("ยังมีสต็อกคงเหลือ" + stocked + " เปลี่ยนการคุม Serial ไม่ได้");
    }
    if (!p.itemType().equals(current.itemType()) && !stocked.isEmpty()) {
      throw ApiException.badRequest("ยังมีสต็อกคงเหลือ" + stocked + " เปลี่ยนเป็นสินค้าบริการไม่ได้");
    }
    dao.update(id, p);
    dao.setLevels(branch, id, p.minStock(), p.maxStock());
    return at(id, branch);
  }

  /** ลบได้เมื่อไม่มีของสาขาใด และไม่อยู่ในโปรที่ยังไม่หมดอายุ (มีการขาย → ขั้น 9); ช่วงราคาของ SKU ลบตามไปด้วย. */
  @Transactional
  public void delete(long id) {
    locks.lock(PRODUCTS);
    branches.first().ifPresent(b -> at(id, b.id()));
    String stocked = stockedNote(id);
    if (!stocked.isEmpty()) {
      throw ApiException.badRequest("ยังมีสต็อกคงเหลือ" + stocked + " ลบไม่ได้");
    }
    List<String> usedBy = promotions.codesUsingProduct(id, today());
    if (!usedBy.isEmpty()) {
      throw ApiException.badRequest("SKU นี้อยู่ในโปรโมชั่น " + String.join(", ", usedBy) + " ลบไม่ได้");
    }
    dao.delete(id);
  }

  // ---------- สต็อก ----------

  /** {@code PUT /products/:id/stock}: non-serial, non-service; delta จำนวนเต็ม ≠ 0; รับเข้า = ทุนเฉลี่ยใหม่, ตัดออก = audit {@code stock_out}. */
  @Transactional
  public Product adjustStock(ShopSession s, BranchContext ctx, long id, StockAdjust body) {
    long branch = ctx.require();
    Product p = at(id, branch, "ไม่พบสินค้า");
    if (ProductRules.isService(p.itemType())) {
      throw ApiException.badRequest("สินค้าบริการไม่มีสต็อก");
    }
    if (p.serialControl()) {
      throw ApiException.badRequest("สินค้าคุม Serial ต้องรับเข้า/ตัดออกด้วย Serial");
    }
    Double raw = body.delta();
    if (raw == null || raw == 0 || raw % 1 != 0 || Math.abs(raw) > Integer.MAX_VALUE) {
      throw ApiException.badRequest("จำนวนไม่ถูกต้อง");
    }
    int delta = raw.intValue();
    String note = body.note() == null ? "" : body.note();
    if (delta > 0) {
      badRequest(ProductRules.purchaseError(p.saleStatus()));
      double cost = body.unitCost() == null ? p.cost() : body.unitCost();
      badRequest(ProductRules.receiptCostError(cost));
      ledger.receive(branch, id, delta, cost, "receive", note);
    } else {
      ledger.issue(branch, id, -delta, "issue", note);
      audit.record(s, "stock_out", branch, "product", id, p.sku(), delta + " — " + note.strip());
    }
    return at(id, branch);
  }

  /** {@code GET /products/:id/movements}: บัตรสต็อกของสาขาเดียว (E15) — สาขาอื่นได้ถ้าทำงานในสาขานั้นได้. */
  @Transactional(readOnly = true)
  public StockCardResult movements(ShopSession s, BranchContext ctx, long id, String from, String to, String branchParam) {
    long here = stockBranch(ctx);
    at(id, here);
    LocalDate fromDate = date(from);
    LocalDate toDate = date(to);
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      throw ApiException.badRequest("วันที่เริ่มต้องไม่เกินวันที่สิ้นสุด");
    }
    long branch = here;
    if (branchParam != null && !branchParam.isEmpty()) {
      boolean allowed = s.branches().stream().anyMatch(b -> Long.toString(b.id()).equals(branchParam.strip()));
      if (!Long.toString(here).equals(branchParam) && !allowed) {
        throw ApiException.forbidden("ไม่มีสิทธิ์ดูบัตรสต็อกของสาขานี้");
      }
      branch = Long.parseLong(branchParam.strip());
    }
    return Costing.buildStockCard(stock.movements(branch, id), fromDate, toDate, ClockConfig.BANGKOK);
  }

  /** {@code GET /products/:id/branch-stock}: ทุกสาขาที่ใช้งาน (ไม่มีแถว = 0). */
  @Transactional(readOnly = true)
  public List<BranchStockRow> branchStock(long id) {
    List<Branch> active = branches.list().stream().filter(Branch::active).toList();
    if (active.isEmpty()) {
      return List.of();
    }
    at(id, active.getFirst().id());
    List<BranchStockRow> rows = new ArrayList<>();
    for (Branch b : active) {
      Product p = at(id, b.id());
      rows.add(new BranchStockRow(b.id(), p.stock(), p.avgCost()));
    }
    return rows;
  }

  // ---------- serial ----------

  @Transactional(readOnly = true)
  public List<SerialNumber> serials(BranchContext ctx, long id) {
    long branch = stockBranch(ctx);
    serialProduct(id, branch);
    return serials.listAt(id, branch);
  }

  /**
   * {@code POST /products/:id/serials}: ทุนต่อเครื่อง (specific identification); serial ที่เคยตัดออกใช้แถวเดิม ย้ายมาสาขานี้ (B18 / E16);
   * อยู่ในคลังสาขาอื่น → 400; {@code fromOtherBranch} = เคยอยู่สาขาอื่น (E32).
   */
  @Transactional
  public SerialReceiveResult receiveSerials(BranchContext ctx, long id, SerialReceive body) {
    long branch = ctx.require();
    Product p = serialProduct(id, branch);
    badRequest(ProductRules.purchaseError(p.saleStatus()));
    List<String> nos = (body.serials() == null ? List.<String>of() : body.serials()).stream()
        .map(x -> x == null ? "" : x.strip().toUpperCase(Locale.ROOT)).toList();
    if (nos.isEmpty()) {
      throw ApiException.badRequest("ไม่มี Serial ที่จะรับเข้า");
    }
    double unitCost = body.unitCost() == null ? p.cost() : body.unitCost();
    badRequest(ProductRules.receiptCostError(unitCost));
    ledger.lock(branch, id);
    Map<Long, String> codes = branches.list().stream().collect(Collectors.toMap(Branch::id, Branch::code));
    List<String> problems = new ArrayList<>();
    List<Optional<SerialNumber>> existing = new ArrayList<>();
    for (int i = 0; i < nos.size(); i++) {
      String serial = nos.get(i);
      Optional<SerialNumber> found = serials.find(serial);
      existing.add(found);
      String format = SerialRules.serialFormatError(serial, p.serialPrefix(), p.serialLength());
      if (format != null) {
        problems.add(serial + ": " + format);
      } else if (nos.indexOf(serial) != i) {
        problems.add(serial + ": ซ้ำในรายการ");
      } else if (found.isPresent() && "in_stock".equals(found.get().status())) {
        problems.add(found.get().branchId() == branch
            ? serial + ": มีอยู่ในคลังแล้ว"
            : serial + ": อยู่ในคลังสาขา " + codes.get(found.get().branchId()));
      } else if (found.isPresent() && "in_transit".equals(found.get().status())) {
        problems.add(serial + ": อยู่ระหว่างโอน");
      }
    }
    if (!problems.isEmpty()) {
      throw ApiException.badRequest("รับเข้าไม่ได้ — " + String.join(", ", problems));
    }
    String typed = body.note() == null ? "" : body.note().strip();
    List<String> returning = new ArrayList<>();
    List<FromOtherBranch> fromOther = new ArrayList<>();
    for (int i = 0; i < nos.size(); i++) {
      if (existing.get(i).isPresent()) {
        returning.add(nos.get(i));
        if (existing.get(i).get().branchId() != branch) {
          fromOther.add(new FromOtherBranch(nos.get(i), codes.get(existing.get(i).get().branchId())));
        }
      }
    }
    String returnNote = typed.isEmpty() ? "รับคืนเข้าคลัง" : typed;
    String movementNote = !typed.isEmpty() ? typed
        : returning.isEmpty() ? ""
        : returning.size() == nos.size() ? "รับคืนเข้าคลัง"
        : "รับคืนเข้าคลัง " + String.join(", ", returning);
    List<SerialNumber> received = new ArrayList<>();
    for (String serial : nos) {
      received.add(serials.receive(serial, id, branch, unitCost, returnNote)
          .orElseThrow(() -> ApiException.badRequest("รับเข้าไม่ได้ — " + serial + ": มีอยู่ในคลังแล้ว")));
    }
    ledger.recordSerials(branch, id, "receive", received.size(), unitCost, received.size() * unitCost, nos, movementNote);
    return new SerialReceiveResult(at(id, branch), received, fromOther);
  }

  /** {@code POST /products/:id/serials/remove} (admin): เฉพาะ serial ในคลังของสาขานี้ (E16); ทุนตัดออก = ทุนของเครื่องเอง. */
  @Transactional
  public Product removeSerials(ShopSession s, BranchContext ctx, long id, SerialRemove body) {
    long branch = ctx.require();
    Product p = serialProduct(id, branch);
    List<Long> ids = body.ids() == null ? List.of() : body.ids();
    if (ids.isEmpty()) {
      throw ApiException.badRequest("ไม่ได้เลือก Serial");
    }
    if (!REMOVE_STATUSES.contains(body.status())) {
      throw ApiException.badRequest("ข้อมูลที่ส่งมาไม่ถูกต้อง");
    }
    ledger.lock(branch, id);
    List<SerialNumber> targets = serials.findAll(ids).stream()
        .filter(x -> x.productId() == id && x.branchId() == branch && "in_stock".equals(x.status())).toList();
    String note = body.note() == null ? "" : body.note();
    if (targets.size() != ids.size() || serials.remove(ids, id, branch, body.status(), note) != ids.size()) {
      throw ApiException.badRequest("มี Serial ที่ไม่อยู่ในคลังแล้ว กรุณาโหลดใหม่");
    }
    double total = targets.stream().mapToDouble(SerialNumber::cost).sum();
    List<String> nos = targets.stream().map(SerialNumber::serial).toList();
    String label = List.of(SerialRules.STATUS_LABEL.get(body.status()), note).stream().filter(x -> !x.isEmpty())
        .collect(Collectors.joining(" · "));
    ledger.recordSerials(branch, id, "issue", -targets.size(), total / targets.size(), -total, nos, label);
    audit.record(s, "serial_remove", branch, "product", id, p.sku(),
        String.join(", ", nos) + " → " + body.status() + " — " + note.strip());
    return at(id, branch);
  }

  // ---------- ภายใน ----------

  /** สาขาที่อ่านสต็อก: X-Branch-Id หรือสาขาแรก. */
  private long stockBranch(BranchContext ctx) {
    if (ctx.branchId() != null) {
      return ctx.branchId();
    }
    return branches.first().map(Branch::id).orElseThrow(() -> ApiException.notFound("ไม่พบสาขา"));
  }

  private Product at(long id, long branch) {
    return at(id, branch, NOT_FOUND);
  }

  private Product at(long id, long branch, String notFound) {
    Product p = dao.findAt(id, branch).orElseThrow(() -> ApiException.notFound(notFound));
    return derived(p, categories.list(), prices.current(today(), id));
  }

  private Product serialProduct(long id, long branch) {
    Product p = at(id, branch);
    if (!p.serialControl()) {
      throw ApiException.badRequest(p.sku() + " ไม่ได้ควบคุม Serial");
    }
    return p;
  }

  /** ค่าที่ server คำนวณ: categoryPath + currentPrice (ราคาของช่วงที่มีผลวันนี้, ไม่มี = null). */
  private static Product derived(Product p, List<Category> cats, Map<Long, Double> prices) {
    return new Product(p.id(), p.sku(), p.name(), p.shortName(), p.categoryId(), CategoryRules.categoryPath(cats, p.categoryId()),
        p.unit(), p.barcode(), p.packUnits(), p.brand(), p.model(), p.itemType(), p.imageUrl(), p.vatType(), p.cost(), p.avgCost(),
        prices.get(p.id()), p.stock(), p.minStock(), p.maxStock(), p.suppliers(), p.warrantyMonths(), p.serialControl(), p.serialPrefix(),
        p.serialLength(), p.saleStatus());
  }

  /** ' (สาขา HQ, BN)' เมื่อสาขาใดยังมีของ (E18) — ระหว่างโอน (ขั้น 6). */
  private String stockedNote(long id) {
    List<String> codes = dao.stockedBranchCodes(id);
    return codes.isEmpty() ? "" : " (สาขา " + String.join(", ", codes) + ")";
  }

  private Set<Long> supplierIds() {
    return new HashSet<>(suppliers.list().stream().map(Supplier::id).toList());
  }

  /** {@code PRODUCT_DEFAULTS}. */
  private static Product defaults() {
    return new Product(null, "", "", "", null, "", "ชิ้น", "", List.of(), "", "", "stock", "", "vat7", 0.0, 0.0, null, 0, 0, 0, List.of(),
        0, false, "", null, "active");
  }

  /** ทับ {@code base} ด้วย field ที่ส่งมา (ค่าที่ server คำนวณไม่ใช้ — {@code withoutDerived()}). */
  private static Product merge(Product base, Product in) {
    return new Product(base.id(), or(in.sku(), base.sku()), or(in.name(), base.name()), or(in.shortName(), base.shortName()),
        in.categoryId() != null ? in.categoryId() : base.categoryId(), base.categoryPath(), or(in.unit(), base.unit()),
        or(in.barcode(), base.barcode()), in.packUnits() != null ? in.packUnits() : base.packUnits(), or(in.brand(), base.brand()),
        or(in.model(), base.model()), or(in.itemType(), base.itemType()), or(in.imageUrl(), base.imageUrl()),
        or(in.vatType(), base.vatType()), in.cost() != null ? in.cost() : base.cost(), base.avgCost(), base.currentPrice(), base.stock(),
        in.minStock() != null ? in.minStock() : base.minStock(), in.maxStock() != null ? in.maxStock() : base.maxStock(),
        in.suppliers() != null ? in.suppliers() : base.suppliers(),
        in.warrantyMonths() != null ? in.warrantyMonths() : base.warrantyMonths(),
        in.serialControl() != null ? in.serialControl() : base.serialControl(), or(in.serialPrefix(), base.serialPrefix()),
        in.serialLength() != null ? in.serialLength() : base.serialLength(), or(in.saleStatus(), base.saleStatus()));
  }

  /**
   * E44: คนที่เห็นทุนไม่ได้ — ไม่ใช้ {@code cost} / {@code suppliers[].cost} จาก body: คงค่าที่เก็บไว้ (ผู้จำหน่ายใหม่ / SKU ใหม่ = 0).
   */
  private static Product withStoredCosts(ShopSession s, Product p, Product stored) {
    if (s.canSeeCost()) {
      return p;
    }
    List<SkuSupplier> links = p.suppliers().stream().map(l -> new SkuSupplier(l.supplierId(), l.supplierSku(),
        stored == null ? 0.0 : stored.suppliers().stream().filter(x -> x.supplierId().equals(l.supplierId())).findFirst()
            .map(SkuSupplier::cost).orElse(0.0),
        l.leadTimeDays(), l.moq(), l.isMain())).toList();
    return new Product(p.id(), p.sku(), p.name(), p.shortName(), p.categoryId(), p.categoryPath(), p.unit(), p.barcode(), p.packUnits(),
        p.brand(), p.model(), p.itemType(), p.imageUrl(), p.vatType(), stored == null ? 0.0 : stored.cost(), p.avgCost(),
        p.currentPrice(), p.stock(), p.minStock(), p.maxStock(), links, p.warrantyMonths(), p.serialControl(), p.serialPrefix(),
        p.serialLength(), p.saleStatus());
  }

  private static LocalDate date(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw ApiException.badRequest("รูปแบบวันที่ต้องเป็น YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("รูปแบบวันที่ต้องเป็น YYYY-MM-DD");
    }
  }

  private static String or(String value, String fallback) {
    return value != null ? value : fallback;
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
