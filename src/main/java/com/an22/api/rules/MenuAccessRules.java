package com.an22.api.rules;

import com.an22.api.model.MenuAccess;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * port จาก {@code core/models/menu-access.model.ts} + รายการเมนูของ {@code core/navigation/menu.config.ts} (path → ป้าย, ตามลำดับเมนู)
 * — หน้าบ้านเพิ่ม / เปลี่ยนเมนู ต้องแก้ {@link #MENU} ด้วย.
 */
public final class MenuAccessRules {

  public static final Map<String, String> MENU = menu();
  public static final List<String> ADMIN_ONLY_MENUS = List.of("users", "account");
  public static final List<String> DEFAULT_USER_MENUS = List.of("dashboard", "pos", "sales", "settings");

  private MenuAccessRules() {}

  private static Map<String, String> menu() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("dashboard", "แดชบอร์ด");
    m.put("pos", "ขายหน้าร้าน");
    m.put("sales", "การขาย");
    m.put("inventory", "คลังสินค้า");
    m.put("transfers", "โอนสินค้า");
    m.put("sku", "จัดการ SKU");
    m.put("pricing", "จัดการราคา");
    m.put("promotions", "โปรโมชั่น");
    m.put("master-data", "ข้อมูลหลัก");
    m.put("users", "ผู้ใช้งาน");
    m.put("account", "บัญชีบริษัท");
    m.put("settings", "ตั้งค่า");
    return Collections.unmodifiableMap(m);
  }

  /** {@code menuAccessError()}: กฎเดียวของการบันทึก (ฟอร์ม + server). */
  public static String menuAccessError(MenuAccess p, List<String> knownPaths) {
    List<String> menus = p == null ? null : p.userMenus();
    if (menus == null || menus.isEmpty()) {
      return "ต้องเลือกอย่างน้อย 1 เมนูให้ผู้ใช้ทั่วไป";
    }
    if (menus.stream().anyMatch(ADMIN_ONLY_MENUS::contains)) {
      return "เมนูผู้ใช้งาน / บัญชีบริษัท เป็นของผู้ดูแลระบบเท่านั้น";
    }
    for (String m : menus) {
      if (!knownPaths.contains(m)) {
        return "ไม่มีเมนู " + m;
      }
    }
    if (menus.stream().distinct().count() != menus.size()) {
      return "เลือกเมนูซ้ำ";
    }
    return null;
  }

  /** {@code canSeeMenu()}: admin ทุกเมนู, role user เฉพาะที่ตั้ง (ไม่รวมเมนูของ admin). */
  public static boolean canSeeMenu(String path, boolean isAdmin, MenuAccess access) {
    if (isAdmin) {
      return true;
    }
    if (ADMIN_ONLY_MENUS.contains(path)) {
      return false;
    }
    return (access == null ? DEFAULT_USER_MENUS : access.userMenus()).contains(path);
  }
}
