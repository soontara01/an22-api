package com.an22.api.sale;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.masterdata.PaymentMethodDao;
import com.an22.api.model.Branch;
import com.an22.api.model.CreditNote;
import com.an22.api.model.Exchange;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.PosTerminal;
import com.an22.api.model.Sale;
import com.an22.api.model.TaxInvoiceBuyer;
import com.an22.api.pos.PosTerminalDao;
import com.an22.api.product.ProductDao;
import com.an22.api.promotion.PromotionDao;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.CreditNoteRules;
import com.an22.api.rules.ListLimits;
import com.an22.api.security.PiiCipher;
import com.an22.api.shift.ShiftDao;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * ใบลดหนี้ (admin — controller ตรวจ) ตาม {@code createCreditNote} ของ mock: {@code creditNoteError()} → {@code shiftBlocker('refund')} → SKU / serial
 * ยังคืนได้ → เลข {@code CN-<สาขา>-YYYYMMDD-NNNN} → ของเข้าคลัง (ที่ COGS) หรือตัดเสีย ที่สาขาที่ออกใบ. ผู้ซื้อ = ของใบกำกับตอนออก (เข้ารหัส).
 */
@Service
public class CreditNoteService {

  private final CreditNoteDao dao;
  private final SaleDao sales;
  private final ExchangeDao exchanges;
  private final TaxInvoiceService invoices;
  private final SaleStock stock;
  private final ProductDao products;
  private final PromotionDao promotions;
  private final PaymentMethodDao methods;
  private final PosTerminalDao terminals;
  private final BranchDao branches;
  private final ShiftDao shifts;
  private final CompanyInfoDao company;
  private final DocNumbers numbers;
  private final PiiCipher cipher;
  private final JsonMapper json;
  private final Audit audit;
  private final Clock clock;

  CreditNoteService(CreditNoteDao dao, SaleDao sales, ExchangeDao exchanges, TaxInvoiceService invoices, SaleStock stock,
      ProductDao products, PromotionDao promotions, PaymentMethodDao methods, PosTerminalDao terminals, BranchDao branches,
      ShiftDao shifts, CompanyInfoDao company, DocNumbers numbers, PiiCipher cipher, JsonMapper json, Audit audit, Clock clock) {
    this.dao = dao;
    this.sales = sales;
    this.exchanges = exchanges;
    this.invoices = invoices;
    this.stock = stock;
    this.products = products;
    this.promotions = promotions;
    this.methods = methods;
    this.terminals = terminals;
    this.branches = branches;
    this.shifts = shifts;
    this.company = company;
    this.numbers = numbers;
    this.cipher = cipher;
    this.json = json;
    this.audit = audit;
    this.clock = clock;
  }

  /** {@code GET /sales/:id/credit-notes}: เก่าก่อน. */
  @Transactional(readOnly = true)
  public List<CreditNote> ofSale(ShopSession s, long saleId) {
    sales.find(saleId).orElseThrow(() -> ApiException.notFound(SaleService.NOT_FOUND));
    return decode(s, dao.ofSale(saleId));
  }

  /**
   * {@code GET /credit-notes}: {@code ?shiftId=} = ใบที่คืนเงินจากกะนั้น (สาขาของกะ, ไม่ต้องมีช่วงวันที่) ไม่งั้นช่วงวันที่ ≤ 366 วันของสาขาตาม
   * {@code ?branchId=}; ใหม่สุดก่อน.
   */
  @Transactional(readOnly = true)
  public List<CreditNote> list(ShopSession s, BranchContext ctx, String from, String to, String shiftParam, String branchParam) {
    Long shiftId = SaleService.positive(shiftParam);
    List<CreditNoteDao.Row> rows;
    if (shiftId != null) {
      String scopeParam = shifts.find(shiftId).map(x -> Long.toString(x.shift().branchId())).orElse(branchParam);
      SaleService.scope(s, ctx, scopeParam, branches.list());
      rows = dao.ofShift(shiftId, ListLimits.QUERY_LIMIT);
    } else {
      ListLimits.ParsedRange parsed = ListLimits.parseRange(from, to);
      badRequest(parsed.error());
      rows = dao.list(parsed.range().from(), parsed.range().to(), SaleService.scope(s, ctx, branchParam, branches.list()),
          ListLimits.QUERY_LIMIT);
    }
    badRequest(ListLimits.rowLimitError(rows.size(), shiftId == null));
    return decode(s, rows);
  }

  /** {@code POST /sales/:id/credit-notes}. */
  @Transactional
  public CreditNote create(ShopSession s, BranchContext ctx, Long terminalId, long saleId, CreditNote.Payload in) {
    long branch = ctx.require();
    Sale sale = sales.lock(saleId).orElseThrow(() -> ApiException.notFound(SaleService.NOT_FOUND));
    List<CreditNote> notes = decode(s, dao.ofSale(saleId));
    List<Exchange> swaps = exchanges.ofSale(saleId);
    CreditNote.Payload p = new CreditNote.Payload(in.lines() == null ? List.of() : in.lines(), in.refunds() == null ? List.of() : in.refunds(),
        in.reason() == null ? "" : in.reason().strip(), in.expectedTotal());
    List<Branch> all = branches.list();
    List<PaymentMethod> paymentMethods = methods.list();
    String today = LocalDate.now(clock).toString();
    badRequest(CreditNoteRules.creditNoteError(sale, notes, p, promotions.list(), paymentMethods, today, swaps, branch, all));
    // คืนเงินจากลิ้นชัก / ช่องทางของกะที่ออกใบ
    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    Long shiftId = SaleService.moneyShift(s, company.get(), shifts.openOfTerminal(terminal), "refund");
    CreditNote.Draft draft = CreditNoteRules.draftCreditNote(sale, notes, p.lines(), promotions.list(), swaps);
    for (CreditNote.Line line : draft.lines()) {
      if (products.sku(line.productId()).isEmpty()) {
        throw ApiException.badRequest("ไม่พบ SKU " + line.sku() + " รับคืนไม่ได้");
      }
      if (line.serial() != null && !stock.isSold(line.serial(), line.productId())) {
        throw ApiException.badRequest("ซีเรียล " + line.serial() + " ไม่อยู่ในสถานะขายแล้ว รับคืนไม่ได้");
      }
    }

    String code = all.stream().filter(b -> b.id() == branch).map(Branch::code).findFirst().orElse(Long.toString(branch));
    String cnNo = numbers.next(CompanyRules.branchDocPrefix("CN", code, today), 4);
    String note = "ลดหนี้ " + cnNo + " (บิล " + sale.orderNo() + ")";
    List<CreditNote.Line> byProduct = draft.lines().stream()
        .sorted(java.util.Comparator.comparingLong(CreditNote.Line::productId)).toList();
    for (CreditNote.Line line : byProduct) {
      stock.putBack(branch, line.productId(), sale.lines().get(line.saleLineIndex()).itemType(), line.qty() * line.factor(), line.cogs(),
          line.serial(), line.restock(), note, note + " · ไม่รับเข้าคลัง");
    }

    TaxInvoiceBuyer buyer = sale.taxInvoiceNo() == null ? null
        : invoices.all(s, saleId).stream().filter(t -> t.invoiceNo().equals(sale.taxInvoiceNo())).map(t -> t.buyer()).findFirst()
            .orElse(null);
    Optional<PosTerminal> device = terminals.list().stream().filter(t -> t.id().equals(terminal)).findFirst();
    CreditNote created = new CreditNote(0, cnNo, sale.id(), sale.orderNo(), sale.taxInvoiceNo(), buyer, sale.date(), null,
        s.user().name(), p.reason(), draft.lines(), draft.deductions(), draft.subtotal(), draft.deduction(), draft.total(), draft.vat(),
        CreditNoteRules.toRefunds(p.refunds(), paymentMethods), shiftId, branch, device.map(PosTerminal::id).orElse(null),
        device.map(t -> t.posId() == null ? "" : t.posId()).orElse(""), sale.vatRegistered());
    long id = dao.insert(created, buyer == null ? null : cipher.encryptString(json.writeValueAsString(buyer), aad(s)));
    audit.record(s, "credit_note", sale.branchId(), "credit_note", id, cnNo,
        "บิล " + sale.orderNo() + " คืนเงิน " + AuditRules.auditBaht(draft.total()));
    return decode(s, List.of(dao.find(id).orElseThrow())).getFirst();
  }

  private List<CreditNote> decode(ShopSession s, List<CreditNoteDao.Row> rows) {
    return rows.stream().map(r -> {
      CreditNote n = r.note();
      TaxInvoiceBuyer buyer = r.buyer() == null ? null : json.readValue(cipher.decryptString(r.buyer(), aad(s)), TaxInvoiceBuyer.class);
      return new CreditNote(n.id(), n.cnNo(), n.saleId(), n.orderNo(), n.taxInvoiceNo(), buyer, n.saleDate(), n.date(), n.cashier(),
          n.reason(), n.lines(), n.deductions(), n.subtotal(), n.deduction(), n.total(), n.vat(), n.refunds(), n.shiftId(), n.branchId(),
          n.terminalId(), n.posId(), n.vatRegistered());
    }).toList();
  }

  private static String aad(ShopSession s) {
    return PiiCipher.aad("dt_credit_notes", "buyer", s.tenantId());
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
