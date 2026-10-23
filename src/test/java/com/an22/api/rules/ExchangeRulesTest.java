package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.config.ClockConfig;
import com.an22.api.model.Branch;
import com.an22.api.model.CreditNote;
import com.an22.api.model.Exchange;
import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code exchange.model.spec.ts} (ยกเว้น warrantyEnd ที่เป็นของหน้าจอ). */
class ExchangeRulesTest {

  private static SaleLine line(long productId, String sku, int qty, String serial, String itemType, Long freeOf) {
    return new SaleLine(productId, sku, "สินค้า", "สินค้า", "ชิ้น", 1, qty, 100, 100, 0, 0, 0, 100, "vat7", itemType, 6.54, List.of(),
        freeOf, serial, 0, 60);
  }

  private static final Instant SOLD = LocalDateTime.of(2026, 10, 1, 10, 0).atZone(ClockConfig.BANGKOK).toInstant();

  private static Sale sale(String status, Long branchId, List<SaleLine> lines) {
    return new Sale(1, "POS-20261001-0001", SOLD, "", "", lines, List.of(), 0, 0, 0, 0, 0, 0, 0, List.of(), 0, status, null, "", null, "none",
        0, List.of(), List.of(), 0, "", "", null, null, null, null, branchId, null, "", true);
  }

  private static final List<SaleLine> LINES = List.of(line(1, "NB", 1, "NB-1", "stock", null), line(2, "MS", 3, null, "stock", null),
      line(9, "SETUP", 1, null, "service", null));
  private static final Sale SALE = sale("paid", null, LINES);
  private static final List<ExchangeRules.ProductRef> MICE = List.of(new ExchangeRules.ProductRef(2, "MS", 5, "ชิ้น"));

  private static Exchange.LineInput in(int index, double qty, String newSerial, boolean restock) {
    return new Exchange.LineInput(index, qty, newSerial, restock);
  }

  private static final Exchange.LineInput SWAP_NOTEBOOK = in(0, 1, "NB-2", false);

  private static String err(Sale sale, List<Exchange.LineInput> lines, String reason, List<CreditNote> notes, List<Exchange> swaps,
      List<ExchangeRules.ProductRef> products) {
    return ExchangeRules.exchangeError(sale, new Exchange.Payload(lines, reason), notes, swaps, 7, false, "2026-10-05", null, List.of(),
        products);
  }

  private static String err(List<Exchange.LineInput> lines, String reason) {
    return err(SALE, lines, reason, List.of(), List.of(), MICE);
  }

  private static Exchange exchange(String date, String oldSerial, String newSerial) {
    return new Exchange(1, "EX-1", 1, "", SOLD, Instant.parse(date), "", "ชำรุด",
        List.of(new Exchange.Line(0, 1, "NB", "", "", "ชิ้น", 1, 1, oldSerial, newSerial, false, 0, 0)), null, null, null, "");
  }

  @Test
  void letsStaffExchangeWithinTheStoreWindowAndAdminsAnyTime() {
    assertThat(ExchangeRules.exchangeDeadline(SALE, 7)).isEqualTo("2026-10-08");
    assertThat(ExchangeRules.exchangeDeadline(SALE, 0)).isNull();
    assertThat(ExchangeRules.exchangeBlocker(SALE, List.of(), 7, false, "2026-10-08", null, List.of())).isNull();
    List<Branch> branches = List.of(new Branch(1, "HQ", "", "00000", "", "", "", true));
    assertThat(ExchangeRules.exchangeBlocker(sale("paid", 1L, LINES), List.of(), 7, false, "2026-10-08", 2L, branches))
        .isEqualTo("บิลนี้ขายที่สาขา HQ — เปลี่ยนสินค้าได้ที่สาขานั้น");
    assertThat(ExchangeRules.exchangeBlocker(SALE, List.of(), 7, false, "2026-10-09", null, List.of()))
        .isEqualTo("เกินกำหนดเปลี่ยนสินค้า 7 วัน — ให้ผู้ดูแลระบบทำรายการ");
    assertThat(ExchangeRules.exchangeBlocker(SALE, List.of(), 7, true, "2026-10-09", null, List.of())).isNull();
    assertThat(ExchangeRules.exchangeBlocker(SALE, List.of(), 0, false, "2027-01-01", null, List.of())).isNull();
    assertThat(ExchangeRules.exchangeBlocker(sale("cancelled", null, LINES), List.of(), 7, false, "2026-10-05", null, List.of()))
        .isEqualTo("เปลี่ยนสินค้าได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว");
  }

  @Test
  void checksLinesQuantitiesHeldSerialsAndTheReason() {
    assertThat(err(List.of(SWAP_NOTEBOOK), "ชำรุด")).isNull();
    assertThat(err(List.of(), "ชำรุด")).isEqualTo("กรุณาเลือกสินค้าที่เปลี่ยน");
    assertThat(err(List.of(SWAP_NOTEBOOK), " ")).isEqualTo("กรุณาระบุเหตุผลการเปลี่ยนสินค้า");
    assertThat(err(List.of(in(0, 1, "", false)), "ชำรุด")).isEqualTo("NB: กรุณาเลือกซีเรียลเครื่องใหม่");
    assertThat(err(List.of(in(0, 1, "NB-1", false)), "ชำรุด")).isEqualTo("NB: ซีเรียลใหม่ต้องไม่ใช่เครื่องเดิม");
    assertThat(err(List.of(in(2, 1, null, false)), "ชำรุด")).isEqualTo("SETUP เป็นบริการ เปลี่ยนไม่ได้");
    Exchange.LineInput mice = in(1, 3, null, true);
    assertThat(err(List.of(mice), "ชำรุด")).isNull();
    CreditNote note = new CreditNote(1, "CN-1", 1, "", null, null, SOLD, SOLD, "", "x",
        List.of(new CreditNote.Line(1, 2, "MS", "", "", "ชิ้น", 1, 2, 0, "vat7", 0, 0, null, false, true)), List.of(), 0, 0, 0, 0, List.of(),
        null, null, null, "", true);
    assertThat(err(SALE, List.of(mice), "x", List.of(note), List.of(), MICE)).isEqualTo("MS เปลี่ยนได้ไม่เกิน 1 ชิ้น");
  }

  @Test
  void needsNonSerialReplacementsOnTheShelfBeforeTheExchange() {
    Exchange.LineInput mice = in(1, 3, null, true);
    java.util.function.IntFunction<String> withStock =
        stock -> err(SALE, List.of(mice), "x", List.of(), List.of(), List.of(new ExchangeRules.ProductRef(2, "MS", stock, "ชิ้น")));
    assertThat(withStock.apply(3)).isNull();
    assertThat(withStock.apply(0)).isEqualTo("MS สต็อกไม่พอเปลี่ยน (คงเหลือ 0 ชิ้น)");
    assertThat(withStock.apply(2)).isEqualTo("MS สต็อกไม่พอเปลี่ยน (คงเหลือ 2 ชิ้น)");
    List<SaleLine> withFreeLines = new ArrayList<>(LINES);
    withFreeLines.add(line(2, "MS", 1, null, "stock", 3L));
    assertThat(err(sale("paid", null, withFreeLines), List.of(in(1, 3, null, true), in(3, 1, null, true)), "x", List.of(), List.of(),
        List.of(new ExchangeRules.ProductRef(2, "MS", 3, "ชิ้น")))).isEqualTo("MS สต็อกไม่พอเปลี่ยน (คงเหลือ 3 ชิ้น)");
    assertThat(err(SALE, List.of(SWAP_NOTEBOOK), "x", List.of(), List.of(), List.of())).isNull();
  }

  @Test
  void followsTheSerialTheCustomerHoldsAcrossExchanges() {
    Exchange first = exchange("2026-10-02T03:00:00Z", "NB-1", "NB-2");
    Exchange second = exchange("2026-10-03T03:00:00Z", "NB-2", "NB-3");
    assertThat(SaleRules.currentSerial(LINES, 0, List.of())).isEqualTo("NB-1");
    assertThat(SaleRules.currentSerial(LINES, 0, List.of(second, first))).isEqualTo("NB-3");
    assertThat(SaleRules.currentSerial(LINES, 1, List.of(first))).isNull();
    assertThat(err(SALE, List.of(in(0, 1, "NB-2", false)), "x", List.of(), List.of(first), MICE))
        .isEqualTo("NB: ซีเรียลใหม่ต้องไม่ใช่เครื่องเดิม");
    Exchange secondNo = new Exchange(2, "EX-2", 1, "", SOLD, second.date(), "", "", second.lines(), null, null, null, "");
    assertThat(ExchangeRules.exchangeNos(List.of(secondNo, first))).containsExactly("EX-1", "EX-2");
  }
}
