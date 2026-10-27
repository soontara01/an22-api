package com.an22.api.sale;

import com.an22.api.audit.Audit;
import com.an22.api.auth.AuthService;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.masterdata.CategoryDao;
import com.an22.api.masterdata.PaymentMethodDao;
import com.an22.api.model.Branch;
import com.an22.api.model.CartItem;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.Exchange;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.PosTerminal;
import com.an22.api.model.Product;
import com.an22.api.model.Promotion;
import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import com.an22.api.model.SalePayload;
import com.an22.api.model.TaxInvoiceBuyer;
import com.an22.api.model.User;
import com.an22.api.pos.PosTerminalDao;
import com.an22.api.price.PriceDao;
import com.an22.api.product.ProductDao;
import com.an22.api.promotion.PromotionDao;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.ListLimits;
import com.an22.api.rules.Money;
import com.an22.api.rules.PosPayment;
import com.an22.api.rules.PosPricing;
import com.an22.api.rules.PosTerminalRules;
import com.an22.api.rules.SaleRules;
import com.an22.api.rules.ShiftRules;
import com.an22.api.rules.TaxInvoiceRules;
import com.an22.api.rules.UserRules;
import com.an22.api.shift.ShiftDao;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * บิลขาย POS — {@code POST /sales} ตรวจตามลำดับ 1–8 ใน api-contract.md (ไม่เปลี่ยนอะไรจนผ่านหมด) แล้วออกเลข, ตัดสต็อกพร้อม COGS (serial =
 * ทุนของเครื่อง, อื่น = ทุนเฉลี่ยขณะนั้น, บริการ = ทุนมาตรฐาน) ผ่าน {@link SaleStock} เรียงตาม productId; ยกเลิกบิล (วันเดียวกัน, สาขาที่ขาย,
 * ไม่ใช่ admin ต้องมี login ของ admin) คืนสต็อกที่ COGS ของบรรทัด.
 */
@Service
public class SaleService {

  static final String NOT_FOUND = TaxInvoiceService.SALE_NOT_FOUND;
  static final String LOCKED = "หน้าจอถูกล็อก — ปลดล็อกก่อน";
  static final String PRICE_CHANGED = "ราคาหรือโปรโมชั่นมีการเปลี่ยนแปลง กรุณาตรวจสอบบิลอีกครั้ง";
  static final String VOID_NEEDS_APPROVAL = "ยกเลิกบิลต้องให้ผู้ดูแลระบบอนุมัติ";
  static final Set<String> STATUSES = Set.of("pending", "paid", "cancelled");

  private final SaleDao dao;
  private final TaxInvoiceService invoices;
  private final ProductDao products;
  private final PriceDao prices;
  private final PromotionDao promotions;
  private final CategoryDao categories;
  private final PaymentMethodDao methods;
  private final PosTerminalDao terminals;
  private final BranchDao branches;
  private final ShiftDao shifts;
  private final SaleStock stock;
  private final ExchangeDao exchanges;
  private final CompanyInfoDao company;
  private final DocNumbers numbers;
  private final AuthService auth;
  private final Audit audit;
  private final Clock clock;

  SaleService(SaleDao dao, TaxInvoiceService invoices, ProductDao products, PriceDao prices, PromotionDao promotions,
      CategoryDao categories, PaymentMethodDao methods, PosTerminalDao terminals, BranchDao branches, ShiftDao shifts, SaleStock stock,
      ExchangeDao exchanges, CompanyInfoDao company, DocNumbers numbers, AuthService auth, Audit audit, Clock clock) {
    this.dao = dao;
    this.invoices = invoices;
    this.products = products;
    this.prices = prices;
    this.promotions = promotions;
    this.categories = categories;
    this.methods = methods;
    this.terminals = terminals;
    this.branches = branches;
    this.shifts = shifts;
    this.stock = stock;
    this.exchanges = exchanges;
    this.company = company;
    this.numbers = numbers;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
  }

  // ---------- ขาย ----------

  /** {@code POST /sales}: ลำดับตรวจ 1–8 ตาม api-contract.md แล้วบันทึก. */
  @Transactional
  public Sale checkout(ShopSession s, BranchContext ctx, Long terminalId, SalePayload body) {
    long branch = ctx.require();
    List<Branch> all = branches.list();
    // 1. เครื่องที่ลงทะเบียน ใช้งาน และอยู่ในสาขานี้ (ใบเสร็จต้องมี POS ID ของเครื่อง — T2 / E8)
    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    List<PosTerminal> registry = terminals.list();
    badRequest(PosTerminalRules.terminalError(terminal, registry, branch, all));
    // E48: หน้าจอของกะถูกล็อก
    Optional<ShiftDao.Stored> open = shifts.openOfTerminal(terminal);
    if (open.isPresent() && open.get().shift().lockedAt() != null) {
      throw ApiException.badRequest(LOCKED);
    }
    // 2. shiftBlocker('sell')
    CompanyInfo info = company.get();
    Long shiftId = moneyShift(s, info, open, "sell");
    // 3. คิดตะกร้าใหม่ที่ server
    String today = LocalDate.now(clock).toString();
    PosPricing.PricedCart cart = price(body, branch, today, info);
    badRequest(PosPricing.cartError(cart));
    // 4. ส่วนลดพิเศษ
    String manualReason = body.manualDiscountReason() == null ? "" : body.manualDiscountReason().strip();
    badRequest(PosPricing.manualDiscountError(cart, manualReason, isAdmin(s), maxPercent(info)));
    // 5. serial ต้องอยู่ในคลังของสาขานี้
    for (PosPricing.PricedLine line : cart.lines()) {
      if (line.serial() != null && !line.serial().isEmpty()) {
        badRequest(stock.serialHereProblem(line.serial(), line.productId(), line.sku(), branch, all, "ขาย"));
      }
    }
    // 6. ยอดต้องตรงกับที่หน้าจอเห็น
    if (body.expectedTotal() == null || Money.round2(body.expectedTotal()) != cart.total()) {
      throw ApiException.badRequest(PRICE_CHANGED);
    }
    // 7. การชำระ
    List<PaymentMethod> paymentMethods = methods.list();
    List<com.an22.api.model.PaymentInput> payments = body.payments() == null ? List.of() : body.payments();
    badRequest(PosPayment.paymentError(cart.total(), payments, paymentMethods));
    PosPayment.PaymentSummary paid = PosPayment.paymentSummary(cart.total(), payments, paymentMethods);
    // 8. ผู้ซื้อ (ใบกำกับเต็มรูปพร้อมบิล)
    TaxInvoiceBuyer buyer = body.buyer() == null ? null : TaxInvoiceRules.normalizeBuyer(body.buyer());
    if (buyer != null) {
      badRequest(TaxInvoiceRules.checkoutBuyerError(buyer, Boolean.TRUE.equals(info.vatRegistered())));
    }

    String code = code(all, branch);
    String orderNo = numbers.next(PosTerminalRules.terminalOrderPrefix(code, terminal, today), 4);
    double[] cogs = issueStock(cart.lines(), branch, orderNo);
    List<SaleLine> lines = new ArrayList<>();
    for (int i = 0; i < cart.lines().size(); i++) {
      PosPricing.PricedLine l = cart.lines().get(i);
      lines.add(new SaleLine(l.productId(), l.sku(), l.name(), l.shortName(), l.unit(), (int) l.factor(), (int) l.qty(), l.unitPrice(),
          l.listPrice(), l.itemDiscount(), l.billDiscount(), l.manualDiscount(), l.amount(), l.vatType(), l.itemType(), l.vat(),
          l.promotionIds(), l.freeOfPromotionId(), l.serial(), l.warrantyMonths(), cogs[i]));
    }
    PosTerminal device = registry.stream().filter(t -> t.id().equals(terminal)).findFirst().orElseThrow();
    boolean manual = cart.manualDiscount() > 0;
    Sale draft = new Sale(0, orderNo, null, s.user().name(), body.customer() == null ? "" : body.customer().strip(), lines,
        paid.payments(), cart.subtotal(), cart.itemDiscount(), cart.billDiscount(), cart.total(), cart.vat(), paid.rounding(),
        paid.change(), cart.billPromotionIds(), (int) cart.itemCount(), "paid", null, "", null, "none", 0, List.of(), List.of(),
        cart.manualDiscount(), manual ? manualReason : "", manual ? s.user().name() : "", shiftId, null, null, null, branch, terminal,
        device.posId() == null ? "" : device.posId(), Boolean.TRUE.equals(info.vatRegistered()));
    long id = dao.insert(draft);
    Sale sale = dao.find(id).orElseThrow();
    if (buyer != null) {
      invoices.create(s, sale, buyer, true, null, null);
      sale = dao.find(id).orElseThrow();
    }
    if (manual) {
      audit.record(s, "manual_discount", branch, "sale", id, orderNo, AuditRules.auditBaht(sale.manualDiscount()) + " — "
          + sale.manualDiscountReason());
    }
    return sale;
  }

  /** {@code POST /sales/cost-check} (E45): คำเตือนต่ำกว่าทุนของตะกร้า (ไม่มีตัวเลข) — ไม่บันทึกอะไร ไม่ต้องมีกะ / เครื่อง. */
  @Transactional(readOnly = true)
  public Map<String, List<PosPricing.CartIssue>> costCheck(BranchContext ctx, SalePayload body) {
    long branch = ctx.branchId() != null ? ctx.branchId() : branches.list().getFirst().id();
    PosPricing.PricedCart cart = price(body, branch, LocalDate.now(clock).toString(), company.get());
    return Map.of("issues", PosPricing.belowCostIssues(cart.issues()));
  }

  // ---------- อ่าน ----------

  /**
   * {@code GET /sales}: {@code ?shiftId=} = บิลที่ขายหรือ void ในกะนั้น (ใช้สาขาของกะ, ไม่ต้องมีช่วงวันที่) ไม่งั้นช่วงวันขาย ≤ 366 วันของสาขาตาม
   * {@code ?branchId=} (E23); ใหม่สุดก่อน.
   */
  @Transactional(readOnly = true)
  public List<Sale> list(ShopSession s, BranchContext ctx, String from, String to, String shiftParam, String branchParam) {
    Long shiftId = positive(shiftParam);
    List<Sale> rows;
    if (shiftId != null) {
      Optional<ShiftDao.Stored> shift = shifts.find(shiftId);
      String scopeParam = shift.map(x -> Long.toString(x.shift().branchId())).orElse(branchParam);
      scope(s, ctx, scopeParam, branches.list());
      rows = dao.ofShift(shiftId, ListLimits.QUERY_LIMIT);
    } else {
      ListLimits.ParsedRange parsed = ListLimits.parseRange(from, to);
      badRequest(parsed.error());
      rows = dao.list(parsed.range().from(), parsed.range().to(), scope(s, ctx, branchParam, branches.list()), ListLimits.QUERY_LIMIT);
    }
    badRequest(ListLimits.rowLimitError(rows.size(), shiftId == null));
    return rows;
  }

  @Transactional(readOnly = true)
  public Sale get(long id) {
    return dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
  }

  // ---------- ยกเลิก / สถานะ ----------

  /**
   * {@code POST /sales/:id/void}: ไม่ใช่ admin ต้องมี login ของ admin (403 / 400 — P3) → {@code voidError()} (D3) → {@code shiftBlocker('void')}
   * → คืนสต็อกที่สาขาที่ขาย (serial กลับเข้าคลัง, อื่นที่ COGS ของบรรทัด) → ยกเลิกใบกำกับเต็มรูป.
   */
  @Transactional
  public Sale voidSale(ShopSession s, BranchContext ctx, Long terminalId, long id, SalePayload.VoidRequest body, String ip) {
    long branch = ctx.require();
    Sale sale = dao.lock(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    String approver = null;
    if (!isAdmin(s)) {
      if (body == null || body.approval() == null) {
        throw ApiException.forbidden(VOID_NEEDS_APPROVAL);
      }
      User found = auth.credentials(s, body.approval().username(), body.approval().password(), ip)
          .orElseThrow(() -> ApiException.badRequest("รหัสผ่านผู้อนุมัติไม่ถูกต้อง"));
      if (!UserRules.hasAdminRights(found.role())) {
        throw ApiException.badRequest("ผู้อนุมัติต้องเป็นผู้ดูแลระบบ");
      }
      approver = found.name();
    }
    String reason = body == null || body.reason() == null ? "" : body.reason().strip();
    List<Branch> all = branches.list();
    badRequest(SaleRules.voidError(sale.status(), sale.date(), sale.branchId(), reason, LocalDate.now(clock).toString(), branch, all));
    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    Long voidShiftId = moneyShift(s, company.get(), shifts.openOfTerminal(terminal), "void");
    // ใบเปลี่ยนวันเดียวกันสลับ serial ไปแล้ว: ลูกค้าคืนเครื่องที่ถืออยู่ตอนนี้
    List<Exchange> swaps = exchanges.ofSale(id);
    for (int i = 0; i < sale.lines().size(); i++) {
      SaleLine line = sale.lines().get(i);
      if (products.sku(line.productId()).isEmpty()) {
        throw ApiException.badRequest("ไม่พบ SKU " + line.sku() + " คืนสต็อกไม่ได้");
      }
      String serial = SaleRules.currentSerial(sale.lines(), i, swaps);
      if (serial != null && !stock.isSold(serial, line.productId())) {
        throw ApiException.badRequest("ซีเรียล " + serial + " ไม่อยู่ในสถานะขายแล้ว คืนสต็อกไม่ได้");
      }
    }
    String note = "ยกเลิกบิล " + sale.orderNo();
    for (int i : byProduct(sale.lines())) {
      SaleLine line = sale.lines().get(i);
      stock.putBack(branch, line.productId(), line.itemType(), line.qty() * line.factor(), line.cogs(),
          SaleRules.currentSerial(sale.lines(), i, swaps), true, note, note);
    }
    dao.voided(id, reason, voidShiftId, s.user().name(), approver);
    invoices.cancelForVoid(id, reason);
    audit.record(s, "sale_void", sale.branchId(), "sale", id, sale.orderNo(),
        AuditRules.auditBaht(sale.total()) + " — " + reason + (approver != null ? " (อนุมัติโดย " + approver + ")" : ""));
    return dao.find(id).orElseThrow();
  }

  /** {@code PUT /sales/:id/status}: เฉพาะออร์เดอร์ก่อน POS (ไม่มีบรรทัด). */
  @Transactional
  public Sale setStatus(long id, SalePayload.Status body) {
    Sale sale = dao.lock(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    if (!sale.lines().isEmpty()) {
      throw ApiException.badRequest("บิลขายหน้าร้านเปลี่ยนสถานะไม่ได้ (ใช้การยกเลิกบิล)");
    }
    if (body == null || !STATUSES.contains(body.status())) {
      throw ApiException.badRequest(com.an22.api.web.ErrorResponse.BAD_REQUEST);
    }
    dao.setStatus(id, body.status());
    return dao.find(id).orElseThrow();
  }

  // ---------- ภายใน ----------

  /**
   * {@code serverPricedCart()}: ราคา / ทุน / สต็อกของสาขานี้ วันนี้ — SKU เฉพาะที่ตะกร้าและของแถมของโปรอ้าง (ไม่โหลดทั้งร้าน).
   */
  private PosPricing.PricedCart price(SalePayload body, long branch, String today, CompanyInfo info) {
    List<CartItem> items = body.items() == null ? List.of() : body.items();
    List<Promotion> promos = promotions.list();
    Set<Long> ids = new LinkedHashSet<>();
    items.stream().map(CartItem::productId).filter(java.util.Objects::nonNull).forEach(ids::add);
    for (Promotion p : promos) {
      if (p.freeGoods() != null) {
        p.freeGoods().items().forEach(i -> ids.add(i.productId()));
      }
    }
    Map<Long, Double> current = prices.current(LocalDate.parse(today), null);
    List<Product> stock = products.listAt(branch, List.copyOf(ids)).stream().map(p -> withPrice(p, current.get(p.id()))).toList();
    return PosPricing.priceCart(items, new PosPricing.PricingContext(stock, promos, categories.list(), today,
        Boolean.TRUE.equals(info.vatRegistered()), branch), body.freeSerials() == null ? List.of() : body.freeSerials(),
        body.billManualDiscount());
  }

  /** ตัดสต็อกทีละบรรทัด (เรียงตาม productId — กัน deadlock) → COGS ตามลำดับบรรทัดของบิล. */
  private double[] issueStock(List<PosPricing.PricedLine> lines, long branch, String orderNo) {
    double[] cogs = new double[lines.size()];
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingLong(i -> lines.get(i).productId()));
    Map<Long, Product> byId = new java.util.HashMap<>();
    for (Product p : products.listAt(branch, lines.stream().map(PosPricing.PricedLine::productId).distinct().toList())) {
      byId.put(p.id(), p);
    }
    for (int i : order) {
      PosPricing.PricedLine line = lines.get(i);
      Product product = byId.get(line.productId());
      String note = (line.freeOfPromotionId() != null ? "ของแถม " : "ขาย ") + orderNo;
      cogs[i] = stock.issue(branch, product.id(), line.sku(), product.itemType(), product.cost(), (int) (line.qty() * line.factor()),
          line.serial(), note);
    }
    return cogs;
  }

  /** {@code moneyShift()}: ระบบกะเปิด → กะเปิดของเครื่องนี้ + {@code shiftBlocker()}; คืน id ของกะ (ปิดระบบกะ = null). */
  static Long moneyShift(ShopSession s, CompanyInfo info, Optional<ShiftDao.Stored> open, String action) {
    if (!Boolean.TRUE.equals(info.requireShift())) {
      return null;
    }
    ShiftRules.ShiftRef ref = open.map(o -> new ShiftRules.ShiftRef(o.shift().status(), o.cashierId(), o.shift().cashier())).orElse(null);
    badRequest(ShiftRules.shiftBlocker(action, true, ref, s.user().id(), UserRules.hasAdminRights(s.user().role())));
    return open.map(o -> o.shift().id()).orElse(null);
  }

  /**
   * {@code listScope()} ของ mock: {@code ?branchId=<id>|all} (ไม่ส่ง = สาขาที่ทำงาน) → {@code branchScopeError()} (E23); null = ทุกสาขา.
   */
  static Long scope(ShopSession s, BranchContext ctx, String branchParam, List<Branch> all) {
    long working = ctx.branchId() != null ? ctx.branchId() : all.getFirst().id();
    Object scope = UserRules.parseBranchScope(branchParam, working);
    if (scope == null) {
      throw ApiException.badRequest("รหัสสาขาไม่ถูกต้อง");
    }
    String refused = UserRules.branchScopeError(scope, s.user(), all);
    if (refused != null) {
      throw ApiException.forbidden(refused);
    }
    return "all".equals(scope) ? null : (Long) scope;
  }

  /** index ของบรรทัดเรียงตาม productId (เดินสต็อกเรียงกัน — กัน deadlock). */
  static List<Integer> byProduct(List<SaleLine> lines) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingLong(i -> lines.get(i).productId()));
    return order;
  }

  private static Product withPrice(Product p, Double price) {
    return new Product(p.id(), p.sku(), p.name(), p.shortName(), p.categoryId(), p.categoryPath(), p.unit(), p.barcode(), p.packUnits(),
        p.brand(), p.model(), p.itemType(), p.imageUrl(), p.vatType(), p.cost(), p.avgCost(), price, p.stock(), p.minStock(),
        p.maxStock(), p.suppliers(), p.warrantyMonths(), p.serialControl(), p.serialPrefix(), p.serialLength(), p.saleStatus());
  }

  private static double maxPercent(CompanyInfo info) {
    return info.manualDiscountMaxPercent() == null ? 0 : info.manualDiscountMaxPercent().doubleValue();
  }

  private static String code(List<Branch> all, long branchId) {
    return all.stream().filter(b -> b.id() == branchId).map(Branch::code).findFirst().orElse(Long.toString(branchId));
  }

  static Long positive(String value) {
    try {
      long n = Long.parseLong(value == null ? "" : value.strip());
      return n > 0 ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isAdmin(ShopSession s) {
    return UserRules.hasAdminRights(s.user().role());
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
