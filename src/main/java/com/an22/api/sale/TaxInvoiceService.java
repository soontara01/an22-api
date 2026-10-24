package com.an22.api.sale;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.model.Branch;
import com.an22.api.model.Sale;
import com.an22.api.model.TaxInvoice;
import com.an22.api.model.TaxInvoiceBuyer;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.ListLimits;
import com.an22.api.rules.SaleRules;
import com.an22.api.rules.TaxInvoiceRules;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * ใบกำกับภาษีเต็มรูป (ตกลง 2026-10-04): ออกพร้อมบิล (POS) หรือทีหลังจากบิล ลงวันที่ / เลขตามวันขาย, 1 ใบที่ใช้ได้ต่อบิล, ผิดให้ยกเลิก + ออกใหม่
 * (admin). ผู้ซื้อเข้ารหัสทั้งก้อน (AAD {@code dt_tax_invoices.buyer:<tenant>}) + hash ของเลขสำหรับค้น ({@code GET /tax-invoices/buyer}).
 */
@Service
public class TaxInvoiceService {

  static final String SALE_NOT_FOUND = "ไม่พบบิลขาย";

  private final TaxInvoiceDao dao;
  private final SaleDao sales;
  private final BranchDao branches;
  private final CompanyInfoDao company;
  private final DocNumbers numbers;
  private final PiiCipher cipher;
  private final JsonMapper json;
  private final Audit audit;

  TaxInvoiceService(TaxInvoiceDao dao, SaleDao sales, BranchDao branches, CompanyInfoDao company, DocNumbers numbers, PiiCipher cipher,
      JsonMapper json, Audit audit) {
    this.dao = dao;
    this.sales = sales;
    this.branches = branches;
    this.company = company;
    this.numbers = numbers;
    this.cipher = cipher;
    this.json = json;
    this.audit = audit;
  }

  /** {@code GET /sales/:id/tax-invoice}: ใบที่ใช้ได้ ไม่งั้นใบล่าสุด (ไม่มี = null — ไม่ใช่ 404). */
  @Transactional(readOnly = true)
  public TaxInvoice current(ShopSession s, long saleId) {
    sales.find(saleId).orElseThrow(() -> ApiException.notFound(SALE_NOT_FOUND));
    return TaxInvoiceRules.currentInvoice(ofSale(s, saleId));
  }

  /** {@code GET /sales/:id/tax-invoices}: ทุกใบ เก่าก่อน. */
  @Transactional(readOnly = true)
  public List<TaxInvoice> all(ShopSession s, long saleId) {
    sales.find(saleId).orElseThrow(() -> ApiException.notFound(SALE_NOT_FOUND));
    return ofSale(s, saleId);
  }

  /** {@code POST /sales/:id/tax-invoice}: ออกทีหลังจากบิล ({@code taxInvoiceError()}). */
  @Transactional
  public TaxInvoice issue(ShopSession s, BranchContext ctx, long saleId, TaxInvoice.Request in) {
    ctx.require();
    Sale sale = sales.lock(saleId).orElseThrow(() -> ApiException.notFound(SALE_NOT_FOUND));
    TaxInvoiceBuyer buyer = TaxInvoiceRules.normalizeBuyer(buyerOf(in));
    badRequest(TaxInvoiceRules.taxInvoiceError(sale.status(), !sale.lines().isEmpty(), sale.taxInvoiceNo(), sale.vatRegistered(), buyer,
        storeVatRegistered()));
    return create(s, sale, buyer, false, null, null);
  }

  /** {@code POST /sales/:id/tax-invoice/reissue} (admin — controller ตรวจ): ยกเลิกใบที่ใช้อยู่แล้วออกใบใหม่อ้างถึงใบเดิม. */
  @Transactional
  public TaxInvoice reissue(ShopSession s, BranchContext ctx, long saleId, TaxInvoice.Request in) {
    ctx.require();
    Sale sale = sales.lock(saleId).orElseThrow(() -> ApiException.notFound(SALE_NOT_FOUND));
    TaxInvoice current = TaxInvoiceRules.currentInvoice(ofSale(s, saleId));
    TaxInvoiceBuyer buyer = TaxInvoiceRules.normalizeBuyer(buyerOf(in));
    String reason = in == null || in.reason() == null ? "" : in.reason().strip();
    badRequest(TaxInvoiceRules.reissueError(sale.status(), current, buyer, reason));
    TaxInvoice replacement = create(s, sale, buyer, current.atSale(), current, reason);
    audit.record(s, "tax_invoice_reissue", sale.branchId(), "tax_invoice", replacement.id(), replacement.invoiceNo(),
        "แทน " + current.invoiceNo() + " — " + reason);
    return replacement;
  }

  /** {@code GET /tax-invoices} (admin — controller ตรวจ): ตามวันที่ใบกำกับ ≤ 366 วัน ของสาขาตาม {@code ?branchId=} (E23) เก่าก่อน. */
  @Transactional(readOnly = true)
  public List<TaxInvoice> report(ShopSession s, BranchContext ctx, String from, String to, String branchParam) {
    ListLimits.ParsedRange parsed = ListLimits.parseRange(from, to);
    badRequest(parsed.error());
    Long scope = SaleService.scope(s, ctx, branchParam, branches.list());
    List<TaxInvoiceDao.Row> rows = dao.list(parsed.range().from(), parsed.range().to(), scope, ListLimits.QUERY_LIMIT);
    badRequest(ListLimits.rowLimitError(rows.size(), true));
    return rows.stream().map(r -> r.decode(b -> decrypt(s, b))).toList();
  }

  /** {@code POST /tax-invoices/buyer-lookup { taxId }}: ผู้ซื้อล่าสุดที่ใช้เลขนี้ (ค้นด้วย hash ตรงทั้งเลข) — ไม่มี = null. */
  @Transactional(readOnly = true)
  public TaxInvoiceBuyer lastBuyer(ShopSession s, String taxId) {
    if (taxId == null || taxId.isBlank()) {
      return null;
    }
    return dao.lastByTaxIdHash(cipher.hashTaxId(s.tenantId(), taxId)).map(r -> decrypt(s, r.buyer())).orElse(null);
  }

  /**
   * ออกเลข + เก็บใบ (ผู้เรียกตรวจกฎแล้ว — ใช้ทั้งตอนขายและออกทีหลัง): เลข / วันที่ตามวันขาย, สาขาของบิล (E21 / E25); ออกพร้อมบิล = issuedAt เวลาบิล.
   * {@code replaces} = ใบที่ยกเลิกแทนด้วยเหตุผล {@code cancelReason} (ยกเลิกก่อน insert — 1 ใบที่ใช้ได้ต่อบิล).
   */
  public TaxInvoice create(ShopSession s, Sale sale, TaxInvoiceBuyer buyer, boolean atSale, TaxInvoice replaces, String cancelReason) {
    String code = branches.list().stream().filter(b -> b.id() == sale.branchId()).map(Branch::code).findFirst()
        .orElse(String.valueOf(sale.branchId()));
    String invoiceNo = numbers.next(CompanyRules.branchDocPrefix("INV", code, SaleRules.saleDay(sale.date())), 4);
    if (replaces != null) {
      dao.cancelValid(sale.id(), cancelReason, invoiceNo);
    }
    Instant issuedAt = atSale && replaces == null ? sale.date() : null;
    long id = dao.insert(invoiceNo, sale.id(), sale.orderNo(), sale.date(), issuedAt,
        cipher.encryptString(json.writeValueAsString(buyer), aad(s)), cipher.hashTaxId(s.tenantId(), buyer.taxId()), s.user().name(),
        replaces == null ? null : replaces.invoiceNo(), atSale, sale.branchId());
    sales.setTaxInvoiceNo(sale.id(), invoiceNo);
    return dao.find(id).orElseThrow().decode(b -> decrypt(s, b));
  }

  /** ยกเลิกใบที่ใช้ได้ของบิล (void) — เหตุผล "ยกเลิกบิล: …". */
  public void cancelForVoid(long saleId, String reason) {
    dao.cancelValid(saleId, "ยกเลิกบิล: " + reason, null);
  }

  private List<TaxInvoice> ofSale(ShopSession s, long saleId) {
    return dao.ofSale(saleId).stream().map(r -> r.decode(b -> decrypt(s, b))).toList();
  }

  private boolean storeVatRegistered() {
    return Boolean.TRUE.equals(company.get().vatRegistered());
  }

  private static TaxInvoiceBuyer buyerOf(TaxInvoice.Request in) {
    return in == null || in.buyer() == null ? new TaxInvoiceBuyer("", "tax_id", "", "none", "", "") : in.buyer();
  }

  private TaxInvoiceBuyer decrypt(ShopSession s, byte[] stored) {
    return json.readValue(cipher.decryptString(stored, aad(s)), TaxInvoiceBuyer.class);
  }

  private static String aad(ShopSession s) {
    return PiiCipher.aad("dt_tax_invoices", "buyer", s.tenantId());
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
