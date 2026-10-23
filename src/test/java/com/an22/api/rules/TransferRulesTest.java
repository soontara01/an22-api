package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Transfer;
import com.an22.api.model.TransferLine;
import com.an22.api.model.TransferPayload;
import com.an22.api.model.TransferReceivePayload;
import com.an22.api.rules.TransferRules.BranchRef;
import com.an22.api.rules.TransferRules.Context;
import com.an22.api.rules.TransferRules.ProductRef;
import com.an22.api.rules.TransferRules.SerialRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code transfer.model.spec.ts}. */
class TransferRulesTest {

  private static final Context CTX = new Context(
      1,
      List.of(new BranchRef(1, "HQ", true), new BranchRef(2, "BN", true), new BranchRef(3, "OLD", false)),
      List.of(new ProductRef(1, "NB", 2, "เครื่อง", true, "stock"), new ProductRef(2, "MS", 5, "ชิ้น", false, "stock"),
          new ProductRef(9, "SV", 0, "ครั้ง", false, "service")),
      List.of(new SerialRef("NB-1", 1, 1, "in_stock"), new SerialRef("NB-2", 1, 2, "in_stock"), new SerialRef("NB-3", 1, 1, "in_transit")));

  private static TransferPayload send(List<TransferPayload.Line> lines, long to) {
    return new TransferPayload(to, lines, "");
  }

  private static TransferPayload send(List<TransferPayload.Line> lines) {
    return send(lines, 2);
  }

  private static TransferPayload.Line line(long productId, double qty, String... serials) {
    return new TransferPayload.Line(productId, qty, List.of(serials));
  }

  @Test
  void checksANewTransferAgainstTheSourceStock() {
    assertThat(TransferRules.transferError(send(List.of(line(2, 5), line(1, 1, "NB-1"))), CTX)).isNull();
    assertThat(TransferRules.transferError(send(List.of(), 1), CTX)).isEqualTo("สาขาปลายทางต้องไม่ใช่สาขาต้นทาง");
    assertThat(TransferRules.transferError(send(List.of(), 3), CTX)).isEqualTo("สาขา OLD ปิดใช้งานแล้ว");
    assertThat(TransferRules.transferError(send(List.of(), 7), CTX)).isEqualTo("กรุณาเลือกสาขาปลายทาง");
    assertThat(TransferRules.transferError(send(List.of()), CTX)).isEqualTo("ยังไม่มีรายการสินค้าที่จะโอน");
    assertThat(TransferRules.transferError(send(List.of(line(2, 6))), CTX)).isEqualTo("MS มีสต็อกไม่พอ (คงเหลือ 5 ชิ้น)");
    assertThat(TransferRules.transferError(send(List.of(line(9, 1))), CTX)).isEqualTo("SV เป็นสินค้าบริการ ไม่มีสต็อก");
    for (String serial : List.of("NB-2", "NB-3")) {
      assertThat(TransferRules.transferError(send(List.of(line(1, 1, serial))), CTX))
          .isEqualTo("ซีเรียล " + serial + " ไม่อยู่ในคลังของ NB ที่สาขานี้");
    }
    assertThat(TransferRules.transferError(send(List.of(line(1, 2, "NB-1"))), CTX)).isEqualTo("NB: จำนวนต้องเท่ากับจำนวน Serial");
    assertThat(TransferRules.transferError(send(List.of(line(2, 1.5))), CTX)).isEqualTo("MS: จำนวนต้องเป็นจำนวนเต็มมากกว่า 0");
    assertThat(TransferRules.transferError(send(List.of(line(2, 1), line(2, 1))), CTX)).isEqualTo("MS ซ้ำในรายการ");
    assertThat(TransferRules.transferError(send(List.of(line(2, 1, "X"))), CTX)).isEqualTo("MS ไม่ได้ควบคุม Serial");
    assertThat(TransferRules.transferError(send(List.of(line(5, 1))), CTX)).isEqualTo("ไม่พบ SKU ในรายการโอน");
  }

  private static Transfer sent(String status) {
    return new Transfer(1, "TR-1", 1, 2, status, null, "", "",
        List.of(new TransferLine(2, "MS", "", "", 3, List.of(), 0, null, List.of()),
            new TransferLine(1, "NB", "", "", 2, List.of("NB-1", "NB-4"), 0, null, List.of())),
        null, null, "", null, null, "");
  }

  private static TransferReceivePayload got(double ms, double nb, List<String> serials, String reason) {
    return new TransferReceivePayload(
        List.of(new TransferReceivePayload.Line(2L, ms, List.of()), new TransferReceivePayload.Line(1L, nb, serials)), reason);
  }

  @Test
  void receivesOnceAtTheDestinationAndAShortfallNeedsAReason() {
    Transfer t = sent("in_transit");
    TransferReceivePayload all = got(3, 2, List.of("NB-1", "NB-4"), "");
    assertThat(TransferRules.receiveTransferError(t, all, 2L)).isNull();
    assertThat(TransferRules.receiveTransferError(t, all, 1L)).isEqualTo("รับของได้ที่สาขาปลายทางเท่านั้น");
    TransferReceivePayload isShort = got(2, 1, List.of("NB-1"), "");
    assertThat(TransferRules.receiveTransferError(t, isShort, 2L)).isEqualTo("กรุณาระบุเหตุผลของสินค้าที่ขาด / เสียหาย");
    assertThat(TransferRules.receiveTransferError(t, got(2, 1, List.of("NB-1"), "กล่องแตก"), 2L)).isNull();
    assertThat(TransferRules.receiveTransferError(t, got(3, 2, List.of("NB-1", "X"), ""), 2L)).isEqualTo("ซีเรียล X ไม่อยู่ในใบโอนนี้");
    assertThat(TransferRules.receiveTransferError(sent("received"), all, 2L)).isEqualTo("ใบโอนนี้รับหรือยกเลิกไปแล้ว");
    assertThat(TransferRules.receiveTransferError(t, got(4, 2, List.of("NB-1", "NB-4"), ""), 2L))
        .isEqualTo("MS: จำนวนที่รับต้องอยู่ระหว่าง 0–3");
    assertThat(TransferRules.receiveTransferError(t, got(3, 2, List.of("NB-1"), ""), 2L))
        .isEqualTo("NB: จำนวนที่รับต้องเท่ากับจำนวน Serial ที่ติ๊ก");
    assertThat(TransferRules.receiveTransferError(t, new TransferReceivePayload(List.of(), ""), 2L))
        .isEqualTo("MS: ไม่ได้ระบุจำนวนที่รับ");
    assertThat(TransferRules.shortQty(new TransferLine(1, "", "", "", 3, List.of(), 0, 2, List.of()))).isEqualTo(1);
    assertThat(TransferRules.shortQty(new TransferLine(1, "", "", "", 3, List.of(), 0, null, List.of()))).isZero();
  }

  @Test
  void cancelsOnlyInTransitAtTheSourceByAnAdminWithAReason() {
    Transfer t = sent("in_transit");
    assertThat(TransferRules.cancelTransferError(t, 1L, true, "ส่งผิด")).isNull();
    assertThat(TransferRules.cancelTransferError(t, 2L, true, "ส่งผิด")).isEqualTo("ยกเลิกได้ที่สาขาต้นทางเท่านั้น");
    assertThat(TransferRules.cancelTransferError(t, 1L, false, "ส่งผิด")).isEqualTo("ยกเลิกใบโอนได้เฉพาะผู้ดูแลระบบ");
    assertThat(TransferRules.cancelTransferError(t, 1L, true, " ")).isEqualTo("กรุณาระบุเหตุผลการยกเลิก");
    assertThat(TransferRules.cancelTransferError(sent("cancelled"), 1L, true, "x")).isEqualTo("ยกเลิกได้เฉพาะใบโอนที่ยังไม่ได้รับ");
  }

  @Test
  void branchDocPrefix() {
    assertThat(CompanyRules.branchDocPrefix("TR", "BN", "2026-10-18")).isEqualTo("TR-BN-20261018-");
  }
}
