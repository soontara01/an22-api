package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.CreditNote;
import com.an22.api.model.Exchange;
import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * port จาก {@code core/models/exchange.model.ts} — ใบเปลี่ยนสินค้า (ตกลง 2026-10-05): SKU / หน่วยเดิม ไม่มีเงินเดิน, พนักงานทำได้ภายใน
 * {@code exchangeDays} วันหลังวันขาย (0 = ไม่จำกัด; admin ได้เสมอ).
 */
public final class ExchangeRules {

  private ExchangeRules() {}

  /** สต็อกของ SKU ในบิลที่สาขานี้ (ของใหม่ต้องมีก่อนเปลี่ยน). */
  public record ProductRef(long id, String sku, int stock, String unit) {}

  /** {@code exchangeDeadline()}: วันสุดท้าย (รวม) ที่พนักงานเปลี่ยนได้; null = ไม่จำกัด. */
  public static String exchangeDeadline(Sale sale, int exchangeDays) {
    return exchangeDays > 0 ? LocalDate.parse(SaleRules.saleDay(sale.date())).plusDays(exchangeDays).toString() : null;
  }

  /** {@code exchangeBlocker()}: ทำไมเปลี่ยนอะไรในบิลนี้ไม่ได้ตอนนี้ (null = มีบรรทัดที่เปลี่ยนได้). */
  public static String exchangeBlocker(Sale sale, List<CreditNote> creditNotes, int exchangeDays, boolean isAdmin, String today,
      Long hereBranchId, List<Branch> branches) {
    if (!"paid".equals(sale.status()) || sale.lines().isEmpty()) {
      return "เปลี่ยนสินค้าได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว";
    }
    String elsewhere = SaleRules.branchMismatchError(sale.branchId(), hereBranchId, branches, "เปลี่ยนสินค้า");
    if (elsewhere != null) {
      return elsewhere;
    }
    String deadline = exchangeDeadline(sale, exchangeDays);
    if (deadline != null && today.compareTo(deadline) > 0 && !isAdmin) {
      return "เกินกำหนดเปลี่ยนสินค้า " + exchangeDays + " วัน — ให้ผู้ดูแลระบบทำรายการ";
    }
    for (int i = 0; i < sale.lines().size(); i++) {
      if (!"service".equals(sale.lines().get(i).itemType()) && CreditNoteRules.remainingQty(sale, creditNotes, i) > 0) {
        return null;
      }
    }
    return "ไม่มีสินค้าในบิลที่เปลี่ยนได้";
  }

  /**
   * {@code exchangeError()}: กฎเดียวของใบเปลี่ยน (serial ในคลังตรวจที่ server ต่อ) — นับเฉพาะบรรทัดที่ qty > 0, serial ใหม่ตัดช่องว่าง.
   */
  public static String exchangeError(Sale sale, Exchange.Payload payload, List<CreditNote> creditNotes, List<Exchange> exchanges,
      int exchangeDays, boolean isAdmin, String today, Long hereBranchId, List<Branch> branches, List<ProductRef> products) {
    String blocker = exchangeBlocker(sale, creditNotes, exchangeDays, isAdmin, today, hereBranchId, branches);
    if (blocker != null) {
      return blocker;
    }
    List<Exchange.LineInput> chosen = payload.lines().stream().filter(l -> l.qty() != null && l.qty() > 0).toList();
    if (chosen.isEmpty()) {
      return "กรุณาเลือกสินค้าที่เปลี่ยน";
    }
    if (new HashSet<>(chosen.stream().map(Exchange.LineInput::saleLineIndex).toList()).size() != chosen.size()) {
      return "เลือกรายการซ้ำ";
    }
    List<String> newSerials = chosen.stream().map(Exchange.LineInput::newSerial).filter(s -> s != null && !s.isEmpty())
        .map(String::strip).toList();
    if (new HashSet<>(newSerials).size() != newSerials.size()) {
      return "ซีเรียลใหม่ซ้ำกัน";
    }
    for (Exchange.LineInput input : chosen) {
      int index = input.saleLineIndex() == null ? -1 : input.saleLineIndex();
      SaleLine line = index >= 0 && index < sale.lines().size() ? sale.lines().get(index) : null;
      if (line == null) {
        return "ไม่พบรายการที่ " + (index + 1) + " ในบิล";
      }
      if ("service".equals(line.itemType())) {
        return line.sku() + " เป็นบริการ เปลี่ยนไม่ได้";
      }
      int held = CreditNoteRules.remainingQty(sale, creditNotes, index);
      if (input.qty() != Math.rint(input.qty()) || input.qty() > held) {
        return line.sku() + " เปลี่ยนได้ไม่เกิน " + held + " " + line.unit();
      }
      if (line.serial() != null) {
        String serial = input.newSerial() == null ? "" : input.newSerial().strip();
        if (serial.isEmpty()) {
          return line.sku() + ": กรุณาเลือกซีเรียลเครื่องใหม่";
        }
        if (serial.equals(SaleRules.currentSerial(sale.lines(), index, exchanges))) {
          return line.sku() + ": ซีเรียลใหม่ต้องไม่ใช่เครื่องเดิม";
        }
      }
    }
    // ของใหม่ (ไม่คุม serial) ออกจากชั้นก่อน — ของที่คืนไม่นับ
    Map<Long, Double> needed = new LinkedHashMap<>();
    for (Exchange.LineInput input : chosen) {
      SaleLine line = sale.lines().get(input.saleLineIndex());
      if (line.serial() != null) {
        continue;
      }
      needed.merge(line.productId(), input.qty() * line.factor(), Double::sum);
    }
    for (Map.Entry<Long, Double> e : needed.entrySet()) {
      ProductRef product = products.stream().filter(p -> p.id() == e.getKey()).findFirst().orElse(null);
      if (product == null) {
        String sku = sale.lines().stream().filter(l -> l.productId() == e.getKey()).map(SaleLine::sku).findFirst()
            .orElse(String.valueOf(e.getKey()));
        return "ไม่พบ SKU " + sku + " เปลี่ยนไม่ได้";
      }
      if (e.getValue() > product.stock()) {
        return product.sku() + " สต็อกไม่พอเปลี่ยน (คงเหลือ " + product.stock() + " " + product.unit() + ")";
      }
    }
    return payload.reason() == null || payload.reason().isBlank() ? "กรุณาระบุเหตุผลการเปลี่ยนสินค้า" : null;
  }

  /** {@code exchangeNos()}: เลขใบเปลี่ยนของบิล เก่าก่อน. */
  public static List<String> exchangeNos(List<Exchange> exchanges) {
    return exchanges.stream().sorted(java.util.Comparator.comparing(Exchange::date)).map(Exchange::exNo).toList();
  }
}
