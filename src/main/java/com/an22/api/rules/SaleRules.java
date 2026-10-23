package com.an22.api.rules;

import com.an22.api.config.ClockConfig;
import com.an22.api.model.Branch;
import java.time.Instant;
import java.util.List;

/** port จาก {@code core/models/sale.model.ts}: วันขาย, สาขาที่ขาย (D3 / E12), กฎยกเลิกบิล. */
public final class SaleRules {

  private SaleRules() {}

  /** {@code saleDay()}: วันท้องถิ่น (Asia/Bangkok) ของบิล 'YYYY-MM-DD'. */
  public static String saleDay(Instant date) {
    return date.atZone(ClockConfig.BANGKOK).toLocalDate().toString();
  }

  /**
   * {@code branchMismatchError()} (D3, E12): void / ใบลดหนี้ / ใบเปลี่ยนทำได้ที่สาขาที่ขายเท่านั้น — บิลไม่มีสาขา (ข้อมูลเก่า) หรือไม่รู้สาขาที่ทำงาน
   * ไม่บล็อกที่นี่.
   */
  public static String branchMismatchError(Long saleBranchId, Long hereBranchId, List<Branch> branches, String action) {
    if (saleBranchId == null || hereBranchId == null || saleBranchId.equals(hereBranchId)) {
      return null;
    }
    String code = branches.stream().filter(b -> b.id() == saleBranchId).map(Branch::code).findFirst().orElse(null);
    return "บิลนี้ขายที่" + (code != null ? "สาขา " + code : "สาขาอื่น") + " — " + action + "ได้ที่สาขานั้น";
  }

  /** {@code voidError()}: ยกเลิกได้เฉพาะบิลที่ชำระแล้ว ของวันนี้ ที่สาขาที่ขาย และมีเหตุผล — บิลข้ามวันใช้ใบลดหนี้. */
  public static String voidError(String status, Instant date, Long saleBranchId, String reason, String today, Long hereBranchId,
      List<Branch> branches) {
    if (!"paid".equals(status)) {
      return "ยกเลิกได้เฉพาะบิลที่ชำระแล้ว";
    }
    String elsewhere = branchMismatchError(saleBranchId, hereBranchId, branches, "ยกเลิก");
    if (elsewhere != null) {
      return elsewhere;
    }
    if (!saleDay(date).equals(today)) {
      return "ยกเลิกได้เฉพาะบิลของวันนี้ บิลข้ามวันต้องออกใบลดหนี้";
    }
    return reason == null || reason.isBlank() ? "กรุณาระบุเหตุผลการยกเลิก" : null;
  }
}
