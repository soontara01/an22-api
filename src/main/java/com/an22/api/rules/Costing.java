package com.an22.api.rules;

import com.an22.api.model.StockCardResult;
import com.an22.api.model.StockCardResult.Balance;
import com.an22.api.model.StockCardResult.Totals;
import com.an22.api.model.StockMovement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * port จาก {@code core/models/costing.model.ts}: serial = ทุนเฉพาะเครื่อง, อื่น = ทุนเฉลี่ยเคลื่อนที่ (ปัด 4 ตำแหน่ง), มูลค่าปัด 2 ตำแหน่ง.
 */
public final class Costing {

  private Costing() {}

  /** {@code round4()}: ทุนเฉลี่ย. */
  public static double round4(double n) {
    return Math.round(n * 10000) / 10000.0;
  }

  /** {@code movingAverage()}: ทุนเฉลี่ยใหม่หลังรับเข้า {@code qtyIn} หน่วยที่ {@code unitCost}. */
  public static double movingAverage(double stock, double avg, double qtyIn, double unitCost) {
    double qty = Math.max(stock, 0) + qtyIn;
    if (qty <= 0) {
      return round4(avg);
    }
    return round4((Math.max(stock, 0) * avg + qtyIn * unitCost) / qty);
  }

  /** {@code effectiveCost()}: ทุนจริงเมื่อมีของ ไม่งั้นทุนมาตรฐาน. */
  public static double effectiveCost(int stock, double avgCost, double cost) {
    return stock > 0 && avgCost > 0 ? avgCost : cost;
  }

  /** {@code costValue()}: มูลค่าคงคลังที่ทุน. */
  public static double costValue(int stock, double avgCost) {
    return Money.round2(stock * avgCost);
  }

  /**
   * {@code buildStockCard()}: ตัดบัตรสต็อกตามวันท้องถิ่น [from, to] — ยอดยกมา = รายการสุดท้ายก่อน {@code from} (+ บรรทัด opening ที่ต้นช่วง,
   * B17), ยอดปลาย = รายการสุดท้ายในช่วง. {@code movements} เรียงเก่าก่อน.
   */
  public static StockCardResult buildStockCard(List<StockMovement> movements, LocalDate from, LocalDate to, ZoneId zone) {
    List<StockMovement> before = new ArrayList<>();
    List<StockMovement> inRange = new ArrayList<>();
    for (StockMovement m : movements) {
      LocalDate day = m.date().atZone(zone).toLocalDate();
      if (from != null && day.isBefore(from)) {
        before.add(m);
      }
      if ((from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))) {
        inRange.add(m);
      }
    }
    int firstReal = 0;
    while (firstReal < inRange.size() && "opening".equals(inRange.get(firstReal).type())) {
      firstReal++;
    }
    List<StockMovement> leading = List.copyOf(inRange.subList(0, firstReal));
    List<StockMovement> shown = List.copyOf(inRange.subList(firstReal, inRange.size()));
    List<StockMovement> broughtForward = new ArrayList<>(before);
    broughtForward.addAll(leading);
    Balance opening = broughtForward.isEmpty() ? new Balance(0, 0, 0) : balanceOf(broughtForward.getLast());
    Balance closing = shown.isEmpty() ? opening : balanceOf(shown.getLast());
    int inQty = 0;
    int outQty = 0;
    double inCost = 0;
    double outCost = 0;
    for (StockMovement m : shown) {
      if ("opening".equals(m.type())) {
        continue;
      }
      if (m.qty() > 0) {
        inQty += m.qty();
        inCost = Money.round2(inCost + m.totalCost());
      } else {
        outQty -= m.qty();
        outCost = Money.round2(outCost - m.totalCost());
      }
    }
    return new StockCardResult(from, to, opening, leading, shown, closing, new Totals(inQty, inCost, outQty, outCost));
  }

  private static Balance balanceOf(StockMovement m) {
    return new Balance(m.balanceQty(), m.balanceAvgCost(), m.balanceValue());
  }
}
