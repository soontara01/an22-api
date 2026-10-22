package com.an22.api.auth;

import com.an22.api.model.Branch;
import com.an22.api.model.Company;
import com.an22.api.model.Subscription;
import com.an22.api.model.User;
import com.an22.api.rules.CostVisibility;
import java.time.LocalDate;
import java.util.List;

/**
 * ผู้ใช้ร้านของ request — อ่านจาก DB ทุก request (สิทธิ์ / สาขา / สถานะบริษัทมีผลทันที). controller รับเป็น parameter ได้.
 *
 * @param branches สาขาที่ใช้งานอยู่ที่ผู้ใช้เข้าได้ ({@code canWorkIn()}) เรียงตาม id
 * @param suspendedReason {@code overdue} / {@code platform} / {@code deletion}, null = ใช้งานอยู่
 */
public record ShopSession(
    long tenantId,
    String companyCode,
    boolean companyActive,
    String suspendedReason,
    LocalDate purgeAfter,
    Subscription subscription,
    String companyName,
    String taxId,
    boolean staffSeeCost,
    User user,
    List<Branch> branches) {

  public static final String ATTR = ShopSession.class.getName();

  /** รอลบข้อมูล (E39) — ทุก endpoint นอก me / company/* / audit-log → 403. */
  public boolean deletionPending() {
    return "deletion".equals(suspendedReason);
  }

  public Company company() {
    return new Company(tenantId, companyCode, companyName, taxId, companyActive, purgeAfter);
  }

  public boolean canSeeCost() {
    return CostVisibility.canSeeCost(user.role(), staffSeeCost);
  }
}
