package com.an22.api.transfer;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.db.DocNumbers;
import com.an22.api.model.Branch;
import com.an22.api.model.Transfer;
import com.an22.api.model.TransferLine;
import com.an22.api.model.TransferPayload;
import com.an22.api.model.TransferReceivePayload;
import com.an22.api.product.ProductDao;
import com.an22.api.rules.CompanyRules;
import com.an22.api.rules.Costing;
import com.an22.api.rules.ListLimits;
import com.an22.api.rules.TransferRules;
import com.an22.api.rules.UserRules;
import com.an22.api.stock.SerialDao;
import com.an22.api.stock.StockLedger;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ใบโอนสินค้า (E26–E29) — กฎตาม {@code handleTransfers} ของ mock: สร้างที่ต้นทาง (ของออกที่ทุนต้นทาง, serial → in_transit),
 * รับครั้งเดียวที่ปลายทาง (เข้าเต็มจำนวนที่ทุนโอน แล้วตัดส่วนที่ขาด — E28), ยกเลิกโดย admin ต้นทาง (ของกลับที่ทุนเดิม — E29).
 * สต็อกเดินผ่าน {@link StockLedger} เรียงตาม productId; รับ / ยกเลิกล็อกแถวใบโอนก่อน (ทำได้ครั้งเดียว).
 */
@Service
public class TransferService {

  static final String NOT_FOUND = "ไม่พบใบโอน";
  static final String NOT_VISIBLE = "ไม่มีสิทธิ์ดูใบโอนของสาขานี้";
  static final String CANCEL_ADMIN = "ยกเลิกใบโอนได้เฉพาะผู้ดูแลระบบ";

  private final TransferDao dao;
  private final ProductDao products;
  private final BranchDao branches;
  private final SerialDao serials;
  private final StockLedger ledger;
  private final DocNumbers numbers;
  private final Audit audit;
  private final Clock clock;

  TransferService(TransferDao dao, ProductDao products, BranchDao branches, SerialDao serials, StockLedger ledger, DocNumbers numbers,
      Audit audit, Clock clock) {
    this.dao = dao;
    this.products = products;
    this.branches = branches;
    this.serials = serials;
    this.ledger = ledger;
    this.numbers = numbers;
    this.audit = audit;
    this.clock = clock;
  }

  /** {@code ?branchId=<id>|all&direction=out|in} (E23) — ไม่ส่งสาขา = สาขาที่ทำงาน (header / สาขาแรก); ใหม่สุดก่อน. */
  @Transactional(readOnly = true)
  public List<Transfer> list(ShopSession s, BranchContext ctx, String branchParam, String direction) {
    List<Branch> all = branches.list();
    long working = ctx.branchId() != null ? ctx.branchId() : all.getFirst().id();
    Object scope = UserRules.parseBranchScope(branchParam, working);
    if (scope == null) {
      throw ApiException.badRequest("รหัสสาขาไม่ถูกต้อง");
    }
    String refused = UserRules.branchScopeError(scope, s.user(), all);
    if (refused != null) {
      throw ApiException.forbidden(refused);
    }
    List<Transfer> rows = dao.list("all".equals(scope) ? null : (Long) scope, direction, ListLimits.QUERY_LIMIT);
    badRequest(ListLimits.rowLimitError(rows.size(), false));
    return rows;
  }

  @Transactional(readOnly = true)
  public Transfer get(ShopSession s, long id) {
    return visible(s, dao.find(id));
  }

  /** {@code POST /transfers} ที่สาขาต้นทาง (E26). */
  @Transactional
  public Transfer create(ShopSession s, BranchContext ctx, TransferPayload in) {
    long from = ctx.require();
    TransferPayload p = normalize(in);
    List<Branch> all = branches.list();
    List<TransferRules.SerialRef> units = new ArrayList<>();
    for (TransferPayload.Line line : p.lines()) {
      for (String serial : line.serials()) {
        serials.find(serial).ifPresent(u -> units.add(new TransferRules.SerialRef(u.serial(), u.productId(), u.branchId(), u.status())));
      }
    }
    TransferRules.Context rules = new TransferRules.Context(
        from,
        all.stream().map(b -> new TransferRules.BranchRef(b.id(), b.code(), b.active())).toList(),
        p.lines().stream().map(TransferPayload.Line::productId).filter(Objects::nonNull).distinct()
            .flatMap(productId -> products.findAt(productId, from).stream())
            .map(x -> new TransferRules.ProductRef(x.id(), x.sku(), x.stock(), x.unit(), x.serialControl(), x.itemType()))
            .toList(),
        units);
    badRequest(TransferRules.transferError(p, rules));

    String transferNo = numbers.next(CompanyRules.branchDocPrefix("TR", code(all, from), today()), 4);
    String note = "โอนไปสาขา " + code(all, p.toBranchId()) + " " + transferNo;
    List<TransferLine> lines = new ArrayList<>(p.lines().size());
    for (TransferPayload.Line line : p.lines()) {
      lines.add(null);
    }
    for (int i : byProduct(p.lines().stream().map(TransferPayload.Line::productId).toList())) {
      TransferPayload.Line input = p.lines().get(i);
      var product = products.findAt(input.productId(), from).orElseThrow();
      double unitCost;
      if (product.serialControl()) {
        ledger.lock(from, product.id());
        double total = sum(serials.costsOf(input.serials()));
        if (serials.takeOut(input.serials(), product.id(), from, "in_transit", note) != input.serials().size()) {
          throw ApiException.badRequest(StockLedger.NOT_ENOUGH);
        }
        unitCost = total / input.serials().size();
        ledger.recordSerials(from, product.id(), "transfer_out", -input.serials().size(), unitCost, -total, input.serials(), note);
      } else {
        // ออกที่ทุนเฉลี่ยขณะนั้น ซึ่งไม่เปลี่ยน (เหมือนการตัดออกทั่วไป)
        unitCost = ledger.issue(from, product.id(), input.qty().intValue(), "transfer_out", note).avgCost();
      }
      lines.set(i, new TransferLine(product.id(), product.sku(), product.name(), product.unit(), input.qty().intValue(),
          input.serials(), Costing.round4(unitCost), null, List.of()));
    }
    long id = dao.insert(transferNo, from, p.toBranchId(), p.note(), s.user().name(), lines);
    return dao.find(id).orElseThrow();
  }

  /** {@code POST /transfers/:id/receive} ที่สาขาปลายทาง (E27 / E28). */
  @Transactional
  public Transfer receive(ShopSession s, BranchContext ctx, long id, TransferReceivePayload in) {
    Transfer t = visible(s, dao.lock(id));
    TransferReceivePayload p = normalize(in);
    badRequest(TransferRules.receiveTransferError(t, p, ctx.require()));
    long to = t.toBranchId();
    List<Branch> all = branches.list();
    String note = "รับโอนจากสาขา " + code(all, t.fromBranchId()) + " " + t.transferNo();
    String shortNote = "ขาด/เสียหายจากการโอน " + t.transferNo() + " · " + p.shortReason();
    List<TransferLine> done = new ArrayList<>();
    for (TransferLine line : sortedLines(t)) {
      TransferReceivePayload.Line got = TransferRules.received(p, line.productId());
      int receivedQty = got.receivedQty().intValue();
      moveIn(to, line, note);
      if (!line.serials().isEmpty()) {
        List<String> lost = line.serials().stream().filter(x -> !got.receivedSerials().contains(x)).toList();
        if (!lost.isEmpty()) {
          ledger.lock(to, line.productId());
          double total = sum(serials.costsOf(lost));
          serials.takeOut(lost, line.productId(), to, "damaged", shortNote);
          ledger.recordSerials(to, line.productId(), "issue", -lost.size(), total / lost.size(), -total, lost, shortNote);
        }
      } else if (line.qty() > receivedQty) {
        ledger.issue(to, line.productId(), line.qty() - receivedQty, "issue", shortNote);
      }
      done.add(new TransferLine(line.productId(), line.sku(), line.name(), line.unit(), line.qty(), line.serials(), line.unitCost(),
          receivedQty, line.serials().isEmpty() ? List.of() : got.receivedSerials()));
    }
    dao.received(id, s.user().name(), p.shortReason(), done);
    return dao.find(id).orElseThrow();
  }

  /** {@code POST /transfers/:id/cancel} — admin ที่สาขาต้นทาง (E29); ลำดับตรวจตาม mock: 404 → มองเห็น 403 → admin 403 → กฎ 400. */
  @Transactional
  public Transfer cancel(ShopSession s, BranchContext ctx, long id, String reasonIn) {
    Transfer t = visible(s, dao.lock(id));
    if (!UserRules.hasAdminRights(s.user().role())) {
      throw ApiException.forbidden(CANCEL_ADMIN);
    }
    String reason = reasonIn == null ? "" : reasonIn.strip();
    badRequest(TransferRules.cancelTransferError(t, ctx.require(), true, reason));
    String note = "ยกเลิกใบโอน " + t.transferNo();
    for (TransferLine line : sortedLines(t)) {
      moveIn(t.fromBranchId(), line, note);
    }
    dao.cancelled(id, s.user().name(), reason);
    audit.record(s, "transfer_cancel", t.fromBranchId(), "transfer", id, t.transferNo(), reason);
    return dao.find(id).orElseThrow();
  }

  /** ของในบรรทัดเข้าคลังสาขานี้ที่ทุนโอน (ปลายทางรับ / ต้นทางเมื่อยกเลิก): serial กลับเป็น in_stock, อื่น ๆ เฉลี่ยทุนใหม่. */
  private void moveIn(long branchId, TransferLine line, String note) {
    if (!line.serials().isEmpty()) {
      ledger.lock(branchId, line.productId());
      double total = sum(serials.costsOf(line.serials()));
      serials.arrive(line.serials(), line.productId(), branchId, note);
      ledger.recordSerials(branchId, line.productId(), "transfer_in", line.serials().size(), total / line.serials().size(), total,
          line.serials(), note);
      return;
    }
    ledger.receive(branchId, line.productId(), line.qty(), line.unitCost(), "transfer_in", note);
  }

  /** ใบที่ผู้ใช้เข้าสาขาต้นทางหรือปลายทางได้ (E23) — ไม่มี 404, เข้าไม่ได้ 403. */
  private Transfer visible(ShopSession s, java.util.Optional<Transfer> found) {
    Transfer t = found.orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    List<Branch> all = branches.list();
    if (UserRules.branchScopeError(t.fromBranchId(), s.user(), all) != null
        && UserRules.branchScopeError(t.toBranchId(), s.user(), all) != null) {
      throw ApiException.forbidden(NOT_VISIBLE);
    }
    return t;
  }

  /** {@code createTransfer} ของ mock: serial trim + ตัวใหญ่, note trim; ค่าที่ไม่ส่ง = ว่าง. */
  private static TransferPayload normalize(TransferPayload in) {
    List<TransferPayload.Line> lines = in.lines() == null ? List.of() : in.lines().stream()
        .map(l -> new TransferPayload.Line(l.productId(), l.qty(),
            l.serials() == null ? List.of() : l.serials().stream().map(x -> x == null ? "" : x.strip().toUpperCase(Locale.ROOT)).toList()))
        .toList();
    return new TransferPayload(in.toBranchId(), lines, in.note() == null ? "" : in.note().strip());
  }

  private static TransferReceivePayload normalize(TransferReceivePayload in) {
    List<TransferReceivePayload.Line> lines = in.lines() == null ? List.of() : in.lines().stream()
        .map(l -> new TransferReceivePayload.Line(l.productId(), l.receivedQty(),
            l.receivedSerials() == null ? List.of() : l.receivedSerials()))
        .toList();
    return new TransferReceivePayload(lines, in.shortReason() == null ? "" : in.shortReason().strip());
  }

  /** ลำดับ index ของบรรทัดเรียงตาม productId (เดินสต็อกเรียงกัน — กัน deadlock). */
  private static List<Integer> byProduct(List<Long> productIds) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < productIds.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparing(productIds::get));
    return order;
  }

  private static List<TransferLine> sortedLines(Transfer t) {
    return t.lines().stream().sorted(Comparator.comparingLong(TransferLine::productId)).toList();
  }

  /** รหัสสาขาสำหรับข้อความ — ไม่รู้จัก = เลข id ({@code branchCode()} ของ mock). */
  private static String code(List<Branch> all, long branchId) {
    return all.stream().filter(b -> b.id() == branchId).map(Branch::code).findFirst().orElse(Long.toString(branchId));
  }

  private String today() {
    return LocalDate.now(clock).toString();
  }

  private static double sum(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).sum();
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }
}
