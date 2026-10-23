package com.an22.api.masterdata;

import com.an22.api.db.TenantLocks;
import com.an22.api.model.Category;
import com.an22.api.model.CategoryPayload;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.Supplier;
import com.an22.api.promotion.PromotionDao;
import com.an22.api.sale.SaleDao;
import com.an22.api.rules.CategoryRules;
import com.an22.api.rules.PaymentMethodRules;
import com.an22.api.rules.Suppliers;
import com.an22.api.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ข้อมูลหลักที่ไม่ขึ้นกับ SKU (ขั้น 4): หมวดหมู่, ผู้จำหน่าย, ช่องทางชำระเงิน — อ่านได้ทุกคน, เขียน: admin (controller ตรวจ).
 * กฎตาม {@code handleCategories / handleSuppliers / handlePaymentMethods} ของ mock.
 */
@Service
public class MasterDataService {

  static final String CATEGORY_NOT_FOUND = "ไม่พบหมวดหมู่";
  static final String SUPPLIER_NOT_FOUND = "ไม่พบผู้จำหน่าย";
  static final String METHOD_NOT_FOUND = "ไม่พบช่องทางชำระเงิน";
  static final String PAYMENT_METHODS = "payment-methods";

  private final CategoryDao categories;
  private final SupplierDao suppliers;
  private final PaymentMethodDao methods;
  private final TenantLocks locks;
  private final PromotionDao promotions;
  private final Clock clock;
  private final SaleDao sales;

  MasterDataService(CategoryDao categories, SupplierDao suppliers, PaymentMethodDao methods, TenantLocks locks, PromotionDao promotions,
      Clock clock, SaleDao sales) {
    this.sales = sales;
    this.promotions = promotions;
    this.clock = clock;
    this.categories = categories;
    this.suppliers = suppliers;
    this.methods = methods;
    this.locks = locks;
  }

  // ---------- หมวดหมู่ ----------

  @Transactional(readOnly = true)
  public List<Category> categories() {
    return categories.list();
  }

  /** ระดับ = ระดับของแม่ + 1 (ไม่มีแม่ = 1). */
  @Transactional
  public Category createCategory(CategoryPayload p) {
    List<Category> all = categories.list();
    badRequest(CategoryRules.categoryError(p, all, null));
    Category parent = p.parentId() == null ? null : all.stream().filter(c -> c.id() == p.parentId()).findFirst().orElse(null);
    badRequest(CategoryRules.parentError(p.parentId(), parent));
    long id = categories.insert(p.code(), p.name().strip(), p.parentId(), parent == null ? 1 : parent.level() + 1,
        p.active() == null || p.active());
    return categories.find(id).orElseThrow();
  }

  /** แก้ได้แค่ code / name / active — ย้ายแม่ไม่ได้. */
  @Transactional
  public Category updateCategory(long id, CategoryPayload p) {
    Category current = categories.find(id).orElseThrow(() -> ApiException.notFound(CATEGORY_NOT_FOUND));
    badRequest(CategoryRules.categoryError(p, categories.list(), id));
    categories.update(id, p.code(), p.name().strip(), p.active() == null ? current.active() : p.active());
    return categories.find(id).orElseThrow();
  }

  /** ลบได้เมื่อไม่มีลูก / SKU / โปรที่ยังไม่หมดอายุอ้าง. */
  @Transactional
  public void deleteCategory(long id) {
    Category current = categories.find(id).orElseThrow(() -> ApiException.notFound(CATEGORY_NOT_FOUND));
    if (!CategoryRules.isLeaf(categories.list(), id)) {
      throw ApiException.badRequest("ยังมีหมวดหมู่ย่อย ลบไม่ได้");
    }
    if (current.productCount() > 0) {
      throw ApiException.badRequest("ยังมี SKU " + current.productCount() + " รายการในหมวดนี้ ลบไม่ได้");
    }
    List<String> usedBy = promotions.codesUsingCategory(id, LocalDate.now(clock));
    if (!usedBy.isEmpty()) {
      throw ApiException.badRequest("หมวดหมู่นี้อยู่ในโปรโมชั่น " + String.join(", ", usedBy) + " ลบไม่ได้");
    }
    categories.delete(id);
  }

  // ---------- ผู้จำหน่าย ----------

  @Transactional(readOnly = true)
  public List<Supplier> suppliers() {
    return suppliers.list();
  }

  @Transactional(readOnly = true)
  public Supplier supplier(long id) {
    return suppliers.find(id).orElseThrow(() -> ApiException.notFound(SUPPLIER_NOT_FOUND));
  }

  @Transactional
  public Supplier createSupplier(Supplier in) {
    Supplier p = Suppliers.normalize(in);
    badRequest(Suppliers.supplierError(p, suppliers.list(), null));
    return suppliers.find(suppliers.insert(p)).orElseThrow();
  }

  /** PUT แทนทั้งตัว (ค่าที่ไม่ส่ง = ค่าเริ่ม) เหมือน mock. */
  @Transactional
  public Supplier updateSupplier(long id, Supplier in) {
    supplier(id);
    Supplier p = Suppliers.normalize(in);
    badRequest(Suppliers.supplierError(p, suppliers.list(), id));
    suppliers.update(id, p);
    return supplier(id);
  }

  /** ลบได้เมื่อไม่ผูก SKU (นับได้ตั้งแต่ขั้น 5). */
  @Transactional
  public void deleteSupplier(long id) {
    Supplier current = supplier(id);
    if (current.productCount() > 0) {
      throw ApiException.badRequest("ยังผูกกับ SKU " + current.productCount() + " รายการ ลบไม่ได้ (ปิดใช้งานแทน)");
    }
    suppliers.delete(id);
  }

  // ---------- ช่องทางชำระเงิน ----------

  @Transactional(readOnly = true)
  public List<PaymentMethod> paymentMethods() {
    return methods.list();
  }

  @Transactional
  public PaymentMethod createPaymentMethod(PaymentMethod in) {
    locks.lock(PAYMENT_METHODS);
    PaymentMethod p = PaymentMethodRules.normalize(in);
    badRequest(PaymentMethodRules.paymentMethodError(p, methods.list(), null));
    return methods.find(methods.insert(p)).orElseThrow();
  }

  /** PUT แทนทั้งตัว (ค่าที่ไม่ส่ง = ค่าเริ่ม) — ลำดับไม่เปลี่ยน. */
  @Transactional
  public PaymentMethod updatePaymentMethod(long id, PaymentMethod in) {
    locks.lock(PAYMENT_METHODS);
    methods.find(id).orElseThrow(() -> ApiException.notFound(METHOD_NOT_FOUND));
    PaymentMethod p = PaymentMethodRules.normalize(in);
    badRequest(PaymentMethodRules.paymentMethodError(p, methods.list(), id));
    methods.update(id, p);
    return methods.find(id).orElseThrow();
  }

  /** {@code PUT /payment-methods/order { ids }}: ต้องส่ง id ครบทุกตัว ไม่ซ้ำ. */
  @Transactional
  public List<PaymentMethod> reorderPaymentMethods(List<Long> ids) {
    locks.lock(PAYMENT_METHODS);
    Set<Long> known = new HashSet<>(methods.list().stream().map(PaymentMethod::id).toList());
    if (ids == null || ids.size() != known.size() || !ids.stream().allMatch(known::remove)) {
      throw ApiException.badRequest("ลำดับไม่ครบหรือมีรายการที่ไม่รู้จัก กรุณาโหลดใหม่");
    }
    for (int i = 0; i < ids.size(); i++) {
      methods.setSortOrder(ids.get(i), i + 1);
    }
    return methods.list();
  }

  /** เงินสดที่ใช้งานตัวสุดท้ายลบไม่ได้ (มีการขายอ้าง → ขั้น 9). */
  @Transactional
  public void deletePaymentMethod(long id) {
    locks.lock(PAYMENT_METHODS);
    methods.find(id).orElseThrow(() -> ApiException.notFound(METHOD_NOT_FOUND));
    if (PaymentMethodRules.isLastActiveCash(methods.list(), id)) {
      throw ApiException.badRequest("ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง ลบไม่ได้");
    }
    if (sales.methodUsed(id)) {
      throw ApiException.badRequest("มีการขายที่ใช้ช่องทางนี้แล้ว ลบไม่ได้ (ปิดใช้งานแทน)");
    }
    methods.delete(id);
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
