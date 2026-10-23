package com.an22.api.stock;

import com.an22.api.rules.Costing;
import com.an22.api.rules.Money;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * จุดเดียวที่แก้สต็อก (ขาย / void / ใบลดหนี้ / ใบเปลี่ยน / โอน / รับเข้า / ปรับ / serial) — เรียกใน transaction ของ service:
 * <ol>
 *   <li>{@code INSERT branch_stock … ON CONFLICT DO NOTHING} → {@code SELECT … FOR UPDATE} (ล็อกจน commit)
 *   <li>คิดในแอปด้วย {@link Costing} (รับเข้า {@code movingAverage()}, ตัดออก avg คงเดิม)
 *   <li>{@code UPDATE branch_stock} → {@code INSERT stock_movements} พร้อมยอดคงเหลือหลังรายการ
 * </ol>
 * ไม่ใช้ {@code UPDATE … SET stock = stock - :qty} ตรง ๆ เพราะทุนเฉลี่ยต้องคิดด้วยกฎที่ port มา และ movement ต้องรู้ยอดหลังรายการ.
 * หลายบรรทัดในเอกสารเดียว: เรียกเรียงตาม {@code productId} (กัน deadlock). ล็อกหลังตรวจกฎผ่านแล้ว (ถือล็อกสั้นที่สุด).
 */
@Component
public class StockLedger {

  public static final String NOT_ENOUGH = "สต็อกคงเหลือไม่พอ";

  private final StockDao stock;
  private final SerialDao serials;

  StockLedger(StockDao stock, SerialDao serials) {
    this.stock = stock;
    this.serials = serials;
  }

  /** รับเข้า non-serial: ทุนเฉลี่ยใหม่ = {@code movingAverage()}. */
  public void receive(long branchId, long productId, int qty, double unitCost, String type, String note) {
    StockDao.Balance b = stock.lock(branchId, productId);
    int after = b.stock() + qty;
    double avg = Costing.movingAverage(b.stock(), b.avgCost(), qty, unitCost);
    stock.set(branchId, productId, after, avg);
    stock.insertMovement(branchId, productId, type, qty, Money.round2(unitCost), Money.round2(qty * unitCost), after, avg,
        Costing.costValue(after, avg), List.of(), note);
  }

  /** ผลของ {@link #issue}: ทุนเฉลี่ยที่ตัดออก + ต้นทุน (COGS) = round2(avg × qty). */
  public record Issued(double avgCost, double cogs) {}

  /** ตัดออก non-serial ที่ทุนเฉลี่ยขณะนั้น (avg ไม่เปลี่ยน) — ไม่พอ → 400. */
  public Issued issue(long branchId, long productId, int qty, String type, String note) {
    StockDao.Balance b = stock.lock(branchId, productId);
    if (b.stock() < qty) {
      throw ApiException.badRequest(NOT_ENOUGH);
    }
    int after = b.stock() - qty;
    stock.set(branchId, productId, after, b.avgCost());
    stock.insertMovement(branchId, productId, type, -qty, Money.round2(b.avgCost()), Money.round2(-qty * b.avgCost()), after,
        b.avgCost(), Costing.costValue(after, b.avgCost()), List.of(), note);
    return new Issued(b.avgCost(), Money.round2(b.avgCost() * qty));
  }

  /**
   * บันทึกการเดินของ SKU serial หลังเปลี่ยนแถว serial แล้ว: สต็อก = นับ serial ในคลัง, ทุนเฉลี่ย = เฉลี่ยทุนของเครื่อง (ปัด 4; ไม่เหลือ = ค่าเดิม).
   * เรียกหลัง {@link #lock} ของ (สาขา, SKU) นั้นแล้ว.
   */
  public void recordSerials(long branchId, long productId, String type, int qty, double unitCost, double totalCost,
      List<String> serialNos, String note) {
    StockDao.Balance b = stock.lock(branchId, productId);
    List<Double> costs = serials.inStockCosts(productId, branchId);
    double avg = costs.isEmpty() ? b.avgCost() : Costing.round4(costs.stream().mapToDouble(Double::doubleValue).sum() / costs.size());
    stock.set(branchId, productId, costs.size(), avg);
    stock.insertMovement(branchId, productId, type, qty, Money.round2(unitCost), Money.round2(totalCost), costs.size(), avg,
        Costing.costValue(costs.size(), avg), serialNos, note);
  }

  /** ล็อก (สาขา, SKU) ก่อนเปลี่ยนแถว serial ของมัน. */
  public void lock(long branchId, long productId) {
    stock.lock(branchId, productId);
  }
}
