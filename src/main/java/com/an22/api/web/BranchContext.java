package com.an22.api.web;

/**
 * {@code X-Branch-Id} ของ request ที่ตรวจแล้ว (ShopSessionFilter: ไม่ใช่สาขาที่ใช้งาน / เข้าไม่ได้ → 403 ก่อนถึง controller).
 *
 * @param branchId null = ไม่ส่ง (การอ่านใช้สาขาแรก — E2; งานเขียนที่ต้องมีสาขาเรียก {@link #require()})
 */
public record BranchContext(Long branchId) {

  public static final String ATTR = BranchContext.class.getName();
  public static final String NO_BRANCH = "ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน";

  /** งานเขียนสต็อก / ขาย / กะ ต้องมีสาขา (E17 / E24) — ไม่มี → 400. */
  public long require() {
    if (branchId == null) {
      throw ApiException.badRequest(NO_BRANCH);
    }
    return branchId;
  }
}
