package com.an22.api.promotion;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.db.TenantLocks;
import com.an22.api.masterdata.CategoryDao;
import com.an22.api.model.Promotion;
import com.an22.api.product.ProductDao;
import com.an22.api.rules.PromotionRules;
import com.an22.api.rules.UserRules;
import com.an22.api.web.ApiException;
import com.an22.api.web.ErrorResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * โปรโมชั่น — กฎตาม {@code handlePromotions} ของ mock: normalize → จำกัดสาขาเฉพาะ admin บริษัท (403, D2 / E6) → {@code promotionError()};
 * เริ่มแล้วแก้ได้บางช่อง ลบไม่ได้. รหัสไม่ซ้ำตรวจใต้ล็อก "promotions" (+ unique ของตาราง); audit {@code promotion_change} ระดับบริษัท.
 */
@Service
public class PromotionService {

  static final String NOT_FOUND = "ไม่พบโปรโมชั่น";
  static final String PROMOTIONS = "promotions";
  static final Set<String> TYPES = Set.of("item_discount", "bill_discount", "free_goods");
  static final Set<String> KINDS = Set.of("percent", "amount");

  private final PromotionDao dao;
  private final ProductDao products;
  private final CategoryDao categories;
  private final BranchDao branches;
  private final TenantLocks locks;
  private final Audit audit;
  private final Clock clock;

  PromotionService(PromotionDao dao, ProductDao products, CategoryDao categories, BranchDao branches, TenantLocks locks, Audit audit,
      Clock clock) {
    this.dao = dao;
    this.products = products;
    this.categories = categories;
    this.branches = branches;
    this.locks = locks;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Promotion> list() {
    return dao.list();
  }

  @Transactional(readOnly = true)
  public Promotion get(long id) {
    return dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
  }

  @Transactional
  public Promotion create(ShopSession s, Promotion in) {
    locks.lock(PROMOTIONS);
    Promotion p = PromotionRules.normalize(in);
    forbidden(PromotionRules.promotionBranchScopeError(p.branchIds(), null, UserRules.isCompanyAdmin(s.user())));
    validate(p, null);
    return saved(s, dao.insert(p), "เพิ่ม");
  }

  /** PUT แทนทั้งตัว (ค่าที่ไม่ส่ง = ค่าเริ่ม) เหมือน mock. */
  @Transactional
  public Promotion update(ShopSession s, long id, Promotion in) {
    locks.lock(PROMOTIONS);
    Promotion current = get(id);
    Promotion p = PromotionRules.normalize(in);
    forbidden(PromotionRules.promotionBranchScopeError(p.branchIds(), current.branchIds(), UserRules.isCompanyAdmin(s.user())));
    validate(p, current);
    dao.update(id, p);
    return saved(s, id, "แก้");
  }

  @Transactional
  public void delete(ShopSession s, long id) {
    locks.lock(PROMOTIONS);
    Promotion current = get(id);
    forbidden(PromotionRules.promotionBranchScopeError(null, current.branchIds(), UserRules.isCompanyAdmin(s.user())));
    if (PromotionRules.hasStarted(current.startDate(), today().toString())) {
      throw ApiException.badRequest("โปรที่เริ่มแล้วลบไม่ได้ (ปิดใช้งานแทน)");
    }
    dao.delete(id);
    audit.record(s, "promotion_change", null, "promotion", id, current.code() + " " + current.name(), "ลบโปรโมชั่น");
  }

  /**
   * {@code promotionError()} — ก่อนนั้นปฏิเสธประเภท / ชนิดส่วนลดที่ไม่รู้จัก (mock รับไว้เงียบ ๆ; ตารางเก็บไม่ได้) ด้วย 400 ข้อความกลาง.
   */
  private void validate(Promotion p, Promotion existing) {
    if (!TYPES.contains(p.type()) || (p.discount() != null && (p.discount().kind() == null || !KINDS.contains(p.discount().kind())))) {
      throw ApiException.badRequest(ErrorResponse.BAD_REQUEST);
    }
    PromotionRules.Context ctx = new PromotionRules.Context(
        products.refs(),
        categories.list().stream().map(c -> new PromotionRules.CategoryRef(c.id(), c.name(), c.active())).toList(),
        dao.list(),
        branches.list().stream().map(b -> new PromotionRules.BranchRef(b.id(), b.active())).toList(),
        today().toString());
    String problem = PromotionRules.promotionError(p, ctx, existing);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }

  private Promotion saved(ShopSession s, long id, String verb) {
    Promotion p = get(id);
    audit.record(s, "promotion_change", null, "promotion", id, p.code() + " " + p.name(),
        verb + "โปรโมชั่น" + (p.enabled() ? "" : " (ปิดใช้งาน)"));
    return p;
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private static void forbidden(String problem) {
    if (problem != null) {
      throw ApiException.forbidden(problem);
    }
  }
}
