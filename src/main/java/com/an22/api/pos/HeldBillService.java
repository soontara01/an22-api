package com.an22.api.pos;

import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.model.HeldBill;
import com.an22.api.rules.PosTerminalRules;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * บิลที่พักไว้ของ POS (E48) ต่อสาขา ({@code X-Branch-Id}) + เครื่อง ({@code X-Terminal-Id}) — มีข้อมูลผู้ซื้อ จึงเข้ารหัสทั้งบิลด้วย
 * {@link PiiCipher} (AAD {@code dt_held_bills.payload:<tenant>}) และไม่ log body ({@code RequestLogFilter}). กฎตาม {@code handleHeldBills} ของ mock.
 */
@Service
public class HeldBillService {

  static final String NOT_FOUND = "ไม่พบบิลที่พักไว้";

  private final HeldBillDao dao;
  private final PosTerminalDao terminals;
  private final BranchDao branches;
  private final PiiCipher cipher;
  private final JsonMapper json;
  private final Clock clock;

  HeldBillService(HeldBillDao dao, PosTerminalDao terminals, BranchDao branches, PiiCipher cipher, JsonMapper json, Clock clock) {
    this.dao = dao;
    this.terminals = terminals;
    this.branches = branches;
    this.cipher = cipher;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<HeldBill> list(ShopSession s, BranchContext ctx, Long terminalId) {
    Place place = place(ctx, terminalId);
    return dao.list(place.branchId(), place.terminalId()).stream().map(row -> {
      HeldBill bill = json.readValue(cipher.decryptString(row.payload(), aad(s)), HeldBill.class);
      return withId(bill, row.id());
    }).toList();
  }

  /** {@code POST /pos/held-bills}: server ออก id; ไม่มีรายการ → 400. */
  @Transactional
  public HeldBill hold(ShopSession s, BranchContext ctx, Long terminalId, HeldBill in) {
    Place place = place(ctx, terminalId);
    if (in.items() == null || in.items().isEmpty()) {
      throw ApiException.badRequest("ไม่มีรายการในบิลที่จะพัก");
    }
    Instant heldAt = heldAt(in.heldAt());
    HeldBill bill = new HeldBill(null, heldAt.toString(), in.customer() == null ? "" : in.customer(), in.items(),
        in.freeSerials() == null ? List.of() : in.freeSerials(), in.buyer() == null || in.buyer().isNull() ? null : in.buyer(),
        in.billManual(), in.manualReason() == null ? "" : in.manualReason(), in.total() == null ? 0.0 : in.total());
    long id = dao.insert(place.branchId(), place.terminalId(), heldAt, cipher.encryptString(json.writeValueAsString(bill), aad(s)));
    return withId(bill, id);
  }

  /** {@code DELETE /pos/held-bills/:id}: เรียกคืน / ทิ้ง — ของสาขา / เครื่องอื่น → 404. */
  @Transactional
  public void remove(BranchContext ctx, Long terminalId, String id) {
    Place place = place(ctx, terminalId);
    long key;
    try {
      key = Long.parseLong(id);
    } catch (NumberFormatException e) {
      throw ApiException.notFound(NOT_FOUND);
    }
    if (!dao.delete(key, place.branchId(), place.terminalId())) {
      throw ApiException.notFound(NOT_FOUND);
    }
  }

  private record Place(long branchId, int terminalId) {}

  /** ต้องมีสาขา (400) และเครื่องในทะเบียนของสาขานั้น ({@code terminalError()} → 400). */
  private Place place(BranchContext ctx, Long terminalId) {
    long branch = ctx.require();
    Integer terminal = terminalId == null || terminalId > Integer.MAX_VALUE ? null : terminalId.intValue();
    String problem = PosTerminalRules.terminalError(terminal, terminals.list(), branch, branches.list());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    return new Place(branch, terminal);
  }

  /** เวลาที่พัก (ISO ที่เครื่องส่ง) — ไม่ส่ง / อ่านไม่ได้ = ตอนนี้. */
  private Instant heldAt(String value) {
    if (value != null) {
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException e) {
        // ใช้เวลาของ server
      }
    }
    return Instant.now(clock);
  }

  private static String aad(ShopSession s) {
    return PiiCipher.aad("dt_held_bills", "payload", s.tenantId());
  }

  private static HeldBill withId(HeldBill b, long id) {
    return new HeldBill(Long.toString(id), b.heldAt(), b.customer(), b.items(), b.freeSerials(), b.buyer(), b.billManual(),
        b.manualReason(), b.total());
  }
}
