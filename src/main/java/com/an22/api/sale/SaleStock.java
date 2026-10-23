package com.an22.api.sale;

import com.an22.api.model.Branch;
import com.an22.api.model.SerialNumber;
import com.an22.api.rules.Money;
import com.an22.api.rules.ProductRules;
import com.an22.api.stock.SerialDao;
import com.an22.api.stock.StockLedger;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * การเดินสต็อกของเอกสารขาย (บิล / void / ใบลดหนี้ / ใบเปลี่ยน) ต่อบรรทัด — ผ่าน {@link StockLedger} เสมอ; ผู้เรียกเรียงบรรทัดตาม productId.
 * ทำงานใน transaction ของ service ที่เรียก.
 */
@Component
public class SaleStock {

  private final SerialDao serials;
  private final StockLedger ledger;

  SaleStock(SerialDao serials, StockLedger ledger) {
    this.serials = serials;
    this.ledger = ledger;
  }

  /** {@code serialHereProblem()} (E16): serial ต้องอยู่ในคลังของ SKU นั้นที่สาขานี้ — null = ใช้ได้. */
  public String serialHereProblem(String serial, long productId, String sku, long branch, List<Branch> all, String what) {
    Optional<SerialNumber> unit = serials.find(serial);
    if (unit.isEmpty() || unit.get().productId() != productId || !"in_stock".equals(unit.get().status())) {
      return "ซีเรียล " + serial + " ไม่อยู่ในคลังของ " + sku;
    }
    if (unit.get().branchId() == branch) {
      return null;
    }
    String code = all.stream().filter(b -> b.id() == unit.get().branchId()).map(Branch::code).findFirst()
        .orElse(Long.toString(unit.get().branchId()));
    return "ซีเรียล " + serial + " อยู่ที่สาขา " + code + " — " + what + "ที่สาขานี้ไม่ได้";
  }

  /** serial นี้ขายแล้วและเป็นของ SKU นั้น (คืน / เปลี่ยนได้). */
  public boolean isSold(String serial, long productId) {
    return serials.find(serial).filter(u -> u.productId() == productId && "sold".equals(u.status())).isPresent();
  }

  /**
   * {@code issueStock()}: ตัดออก {@code baseQty} หน่วยฐาน → COGS (บริการ = ทุนมาตรฐาน ไม่เดินสต็อก; serial = ทุนของเครื่อง; อื่น = ทุนเฉลี่ยขณะนั้น).
   */
  public double issue(long branch, long productId, String sku, String itemType, double standardCost, int baseQty, String serial, String note) {
    if (ProductRules.isService(itemType)) {
      return Money.round2(standardCost * baseQty);
    }
    if (serial != null) {
      ledger.lock(branch, productId);
      double cost = serials.find(serial).orElseThrow().cost();
      if (serials.takeOut(List.of(serial), productId, branch, "sold", note) != 1) {
        throw ApiException.badRequest("ซีเรียล " + serial + " ไม่อยู่ในคลังของ " + sku);
      }
      ledger.recordSerials(branch, productId, "issue", -1, cost, -cost, List.of(serial), note);
      return Money.round2(cost);
    }
    return ledger.issue(branch, productId, baseQty, "issue", note).cogs();
  }

  /**
   * ของที่ขายไปกลับมาที่สาขานี้ (void / ใบลดหนี้ / ใบเปลี่ยน): serial เข้าคลังที่ทุนของเครื่อง หรือ (restock false) ตัดเสีย; อื่นเข้าคลังที่
   * {@code cogs / baseQty} หรือไม่เข้าคลัง; บริการไม่เดินสต็อก.
   */
  public void putBack(long branch, long productId, String itemType, int baseQty, double cogs, String serial, boolean restock, String note,
      String writeOffNote) {
    if (ProductRules.isService(itemType)) {
      return;
    }
    if (serial != null) {
      if (!restock) {
        serials.writeOffSold(serial, productId, branch, writeOffNote);
        return;
      }
      ledger.lock(branch, productId);
      double cost = serials.find(serial).orElseThrow().cost();
      serials.returnSold(serial, productId, branch, note);
      ledger.recordSerials(branch, productId, "receive", 1, cost, cost, List.of(serial), note);
      return;
    }
    if (restock) {
      ledger.receive(branch, productId, baseQty, cogs / baseQty, "receive", note);
    }
  }

  /** ทุนของ serial (costIn ของใบเปลี่ยน). */
  public double serialCost(String serial) {
    return serials.find(serial).orElseThrow().cost();
  }
}
