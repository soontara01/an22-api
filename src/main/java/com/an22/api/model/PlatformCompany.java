package com.an22.api.model;

import com.an22.api.rules.PlanRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code PlatformCompanyRow} / {@code PlatformCompanyDetail} (platform.model.ts, E36) — รายการใช้ {@link Row}, หน้าบริษัทใช้ {@link Detail}
 * (= Row + ใบแจ้งหนี้ใหม่สุดก่อน, owner, เหตุผลที่ระงับ).
 */
public final class PlatformCompany {

  private PlatformCompany() {}

  /** @param openAmount ยอดใบแจ้งหนี้ที่ยังไม่ชำระ (บาท); deletedAt ลบข้อมูลแล้ว (แถวคงไว้ — รหัสห้ามใช้ซ้ำ) */
  public record Row(
      long id, String code, String name, String taxId, String planId, boolean active, String suspendedReason, String status,
      PlanRules.Usage usage, Subscription subscription, BigDecimal openAmount, Instant deletedAt, CompanyDeletion deletion) {}

  public record Owner(long id, String name, String username, String email) {}

  public record Detail(
      long id, String code, String name, String taxId, String planId, boolean active, String suspendedReason, String status,
      PlanRules.Usage usage, Subscription subscription, BigDecimal openAmount, Instant deletedAt, CompanyDeletion deletion,
      List<BillingInvoice> invoices, Owner owner, String suspendNote) {

    public static Detail of(Row r, List<BillingInvoice> newestFirst, Owner owner, String suspendNote) {
      return new Detail(r.id(), r.code(), r.name(), r.taxId(), r.planId(), r.active(), r.suspendedReason(), r.status(), r.usage(),
          r.subscription(), r.openAmount(), r.deletedAt(), r.deletion(), newestFirst, owner, suspendNote);
    }
  }
}
