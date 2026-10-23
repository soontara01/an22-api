package com.an22.api.price;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.db.TenantLocks;
import com.an22.api.model.SkuPrice;
import com.an22.api.product.ProductDao;
import com.an22.api.rules.PriceRules;
import com.an22.api.web.ApiException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ราคาขายตามช่วงวัน (D2: ราคาเดียวทั้งบริษัท) — กฎตาม {@code handlePrices} ของ mock: PUT แทนทั้งตัว, ช่วงของ SKU เดียวกันห้ามทับ
 * (ตรวจใต้ล็อก "prices" + EXCLUDE ของตารางเป็นตาข่ายสุดท้าย); audit {@code price_change} ระดับบริษัท.
 */
@Service
public class PriceService {

  static final String NOT_FOUND = "ไม่พบช่วงราคา";
  static final String PRICES = "prices";

  private final PriceDao dao;
  private final ProductDao products;
  private final TenantLocks locks;
  private final Audit audit;
  private final Clock clock;

  PriceService(PriceDao dao, ProductDao products, TenantLocks locks, Audit audit, Clock clock) {
    this.dao = dao;
    this.products = products;
    this.locks = locks;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SkuPrice> list(Long productId) {
    return dao.list(productId);
  }

  @Transactional
  public SkuPrice create(ShopSession s, SkuPrice in) {
    locks.lock(PRICES);
    SkuPrice p = validated(in, null);
    long id = dao.insert(p);
    return saved(s, id, "เพิ่ม");
  }

  @Transactional
  public SkuPrice update(ShopSession s, long id, SkuPrice in) {
    locks.lock(PRICES);
    dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    dao.update(id, validated(in, id));
    return saved(s, id, "แก้");
  }

  @Transactional
  public void delete(ShopSession s, long id) {
    locks.lock(PRICES);
    SkuPrice p = dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    dao.delete(id);
    audit.record(s, "price_change", null, "price", id,
        sku(p.productId()) + " ฿" + PriceRules.jsNumber(p.price()) + " ตั้งแต่ " + p.startDate(), "ลบช่วงราคา");
  }

  /** {@code validate} ของ mock: SKU ต้องมีจริง แล้ว {@link PriceRules#priceError}; endDate '' → null, note ไม่ส่ง → ''. */
  private SkuPrice validated(SkuPrice in, Long exceptId) {
    String end = in.endDate() == null || in.endDate().isEmpty() ? null : in.endDate();
    if (in.productId() == null || products.sku(in.productId()).isEmpty()) {
      throw ApiException.badRequest("ไม่พบ SKU");
    }
    String problem = PriceRules.priceError(in.price(), in.productId(), in.startDate(), end, dao.periods(in.productId()), exceptId);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    return new SkuPrice(null, in.productId(), in.price(), in.startDate(), end, in.note() == null ? "" : in.note());
  }

  private SkuPrice saved(ShopSession s, long id, String verb) {
    SkuPrice p = dao.find(id).orElseThrow();
    audit.record(s, "price_change", null, "price", id, sku(p.productId()) + " ฿" + PriceRules.jsNumber(p.price()),
        verb + "ช่วงราคา " + p.startDate() + " – " + (p.endDate() != null ? p.endDate() : "ไม่มีกำหนด"));
    return p;
  }

  private String sku(long productId) {
    return products.sku(productId).orElse("");
  }
}
