package com.an22.api.sale;

import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.CompanyInfoDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.model.Branch;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.CreditNote;
import com.an22.api.model.Exchange;
import com.an22.api.model.PosTerminal;
import com.an22.api.model.Product;
import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import com.an22.api.pos.PosTerminalDao;
import com.an22.api.product.ProductDao;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.ExchangeRules;
import com.an22.api.rules.Money;
import com.an22.api.rules.SaleRules;
import com.an22.api.rules.UserRules;
import com.an22.api.shift.ShiftDao;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ใบเปลี่ยนสินค้า ตาม {@code createExchange} ของ mock: {@code exchangeError()} (ของใหม่ต้องมีในสต็อกก่อน) + serial เดิมยังขายแล้ว / serial ใหม่อยู่ในคลัง
 * สาขานี้ → เลข {@code EX-<สาขา>-YYYYMMDD-NNNN} → ของใหม่ออกก่อน (ทุนแบบขาย) แล้วของเดิมเข้า (ทุนตอนขาย) หรือตัดเสีย. ไม่มีเงินเดิน — ไม่ต้องมีกะ.
 */
@Service
public class ExchangeService {

  private final ExchangeDao dao;
  private final SaleDao sales;
  private final CreditNoteService creditNotes;
  private final SaleStock stock;
  private final ProductDao products;
  private final PosTerminalDao terminals;
  private final BranchDao branches;
  private final ShiftDao shifts;
  private final CompanyInfoDao company;
  private final DocNumbers numbers;
  private final Clock clock;

  ExchangeService(ExchangeDao dao, SaleDao sales, CreditNoteService creditNotes, SaleStock stock, ProductDao products,
      PosTerminalDao terminals, BranchDao branches, ShiftDao shifts, CompanyInfoDao company, DocNumbers numbers, Clock clock) {
    this.dao = dao;
    this.sales = sales;
    this.creditNotes = creditNotes;
    this.stock = stock;
    this.products = products;
    this.terminals = terminals;
    this.branches = branches;
    this.shifts = shifts;
    this.company = company;
    this.numbers = numbers;
    this.clock = clock;
  }

  /** {@code GET /sales/:id/exchanges}: เก่าก่อน. */
  @Transactional(readOnly = true)
  public List<Exchange> ofSale(long saleId) {
    sales.find(saleId).orElseThrow(() -> ApiException.notFound(SaleService.NOT_FOUND));
    return dao.ofSale(saleId);
  }

  /** {@code POST /sales/:id/exchanges} — พนักงานทำได้ภายในกำหนด (admin ได้เสมอ). */
  @Transactional
  public Exchange create(ShopSession s, BranchContext ctx, Long terminalId, long saleId, Exchange.Payload in) {
    long branch = ctx.require();
    Sale sale = sales.lock(saleId).orElseThrow(() -> ApiException.notFound(SaleService.NOT_FOUND));
    List<Exchange> swaps = dao.ofSale(saleId);
    List<CreditNote> notes = creditNotes.ofSale(s, saleId);
    List<Exchange.LineInput> lines = (in.lines() == null ? List.<Exchange.LineInput>of() : in.lines()).stream()
        .filter(l -> l.qty() != null && l.qty() > 0)
        .map(l -> new Exchange.LineInput(l.saleLineIndex(), l.qty(),
            l.newSerial() == null || l.newSerial().isBlank() ? null : l.newSerial().strip(), l.restock()))
        .toList();
    Exchange.Payload p = new Exchange.Payload(lines, in.reason() == null ? "" : in.reason().strip());
    List<Branch> all = branches.list();
    CompanyInfo info = company.get();
    String today = LocalDate.now(clock).toString();
    List<Long> ids = sale.lines().stream().map(SaleLine::productId).distinct().toList();
    List<ExchangeRules.ProductRef> refs = products.listAt(branch, ids).stream()
        .map(x -> new ExchangeRules.ProductRef(x.id(), x.sku(), x.stock(), x.unit())).toList();
    badRequest(ExchangeRules.exchangeError(sale, p, notes, swaps, info.exchangeDays() == null ? 0 : info.exchangeDays().intValue(),
        UserRules.hasAdminRights(s.user().role()), today, branch, all, refs));
    for (Exchange.LineInput input : p.lines()) {
      SaleLine line = sale.lines().get(input.saleLineIndex());
      if (products.sku(line.productId()).isEmpty()) {
        throw ApiException.badRequest("ไม่พบ SKU " + line.sku() + " เปลี่ยนไม่ได้");
      }
      if (line.serial() == null) {
        continue;
      }
      String old = SaleRules.currentSerial(sale.lines(), input.saleLineIndex(), swaps);
      if (!stock.isSold(old, line.productId())) {
        throw ApiException.badRequest("ซีเรียล " + old + " ไม่อยู่ในสถานะขายแล้ว เปลี่ยนไม่ได้");
      }
      badRequest(stock.serialHereProblem(input.newSerial() == null ? "" : input.newSerial(), line.productId(), line.sku(), branch, all,
          "เปลี่ยนให้ลูกค้า"));
    }

    String code = all.stream().filter(b -> b.id() == branch).map(Branch::code).findFirst().orElse(Long.toString(branch));
    String exNo = numbers.next(CompanyRules.branchDocPrefix("EX", code, today), 4);
    String note = "เปลี่ยนสินค้า " + exNo + " (บิล " + sale.orderNo() + ")";
    Map<Long, Product> byId = new HashMap<>();
    products.listAt(branch, ids).forEach(x -> byId.put(x.id(), x));
    List<Exchange.Line> out = new ArrayList<>(p.lines().size());
    p.lines().forEach(x -> out.add(null));
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < p.lines().size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingLong(i -> sale.lines().get(p.lines().get(i).saleLineIndex()).productId()));
    for (int k : order) {
      Exchange.LineInput input = p.lines().get(k);
      SaleLine line = sale.lines().get(input.saleLineIndex());
      Product product = byId.get(line.productId());
      int qty = input.qty().intValue();
      int baseQty = qty * line.factor();
      boolean restock = Boolean.TRUE.equals(input.restock());
      String oldSerial = line.serial() != null ? SaleRules.currentSerial(sale.lines(), input.saleLineIndex(), swaps) : null;
      String newSerial = line.serial() != null ? input.newSerial() : null;
      // ของใหม่ออกจากชั้นก่อน (ทุนแบบขาย) แล้วของเดิมจึงกลับเข้า — ของที่คืนจึงไม่ถูกส่งออกไปทันที
      double costOut = stock.issue(branch, line.productId(), line.sku(), product.itemType(), product.cost(), baseQty, newSerial, note);
      double costIn;
      if (oldSerial != null) {
        costIn = Money.round2(stock.serialCost(oldSerial));
        stock.putBack(branch, line.productId(), line.itemType(), 1, costIn, oldSerial, restock, note, note + " · ชำรุด");
      } else {
        double unitCost = line.cogs() / (line.qty() * line.factor());
        costIn = Money.round2(unitCost * baseQty);
        stock.putBack(branch, line.productId(), line.itemType(), baseQty, unitCost * baseQty, null, restock, note, note);
      }
      out.set(k, new Exchange.Line(input.saleLineIndex(), line.productId(), line.sku(), line.name(), line.shortName(), line.unit(),
          line.factor(), qty, oldSerial, newSerial, restock, costIn, costOut));
    }

    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    Optional<PosTerminal> device = terminals.list().stream().filter(t -> t.id().equals(terminal)).findFirst();
    // ไม่มีเงินเดิน จึงไม่ต้องมีกะ — มีกะเปิดอยู่ก็นับในรายงานของกะนั้น
    Long shiftId = Boolean.TRUE.equals(info.requireShift())
        ? shifts.openOfTerminal(terminal).map(x -> x.shift().id()).orElse(null) : null;
    long id = dao.insert(new Exchange(0, exNo, sale.id(), sale.orderNo(), sale.date(), null, s.user().name(), p.reason(), out, shiftId,
        branch, device.map(PosTerminal::id).orElse(null), device.map(t -> t.posId() == null ? "" : t.posId()).orElse("")));
    return dao.find(id).orElseThrow();
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
