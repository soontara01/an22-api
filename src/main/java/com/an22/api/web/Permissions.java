package com.an22.api.web;

import com.an22.api.auth.ShopSession;
import com.an22.api.rules.UserRules;

/** ตรวจสิทธิ์ที่ controller (403 ตาม {@code adminOnlyError()} ของ mock / saas-plan หัวข้อ 17). */
public final class Permissions {

  public static final String COMPANY_ADMIN_ONLY = "ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)";

  private Permissions() {}

  /** admin หรือ owner — อื่น 403 ด้วยข้อความของ endpoint. */
  public static void requireAdmin(ShopSession s, String message) {
    if (!UserRules.hasAdminRights(s.user().role())) {
      throw ApiException.forbidden(message);
    }
  }

  /** owner หรือ admin ทุกสาขา (E6). */
  public static void requireCompanyAdmin(ShopSession s) {
    if (!UserRules.isCompanyAdmin(s.user())) {
      throw ApiException.forbidden(COMPANY_ADMIN_ONLY);
    }
  }
}
