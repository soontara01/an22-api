package com.an22.api.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantB;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code /settings/*} — อ่านได้ทุกคน, เขียนตามสิทธิ์ (E6 / E11), audit. */
class SettingsIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantB tenantB;

  private Company c;
  private String owner;
  private String boss;
  private String staff;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    staff = login(c.code(), "staff", TestCompanies.PASSWORD);
  }

  private static Map<String, Object> companyInfo(String name) {
    Map<String, Object> m = new HashMap<>();
    m.put("name", name);
    m.put("taxId", "0-1055-50123-45-1");
    m.put("vatRegistered", true);
    m.put("taxInvoicePaper", "80mm");
    m.put("exchangeDays", 3);
    m.put("manualDiscountMaxPercent", 2.5);
    m.put("requireShift", false);
    m.put("shiftVarianceLimit", 50);
    m.put("shiftMaxHours", 10);
    m.put("staffSeeCost", true);
    return m;
  }

  @Test
  void demoCompanySettingsAreReadableByEveryone() throws Exception {
    String somchai = login("DEMO", "somchai", "1234");
    mvc.perform(as(somchai, get("/settings/company")))
        .andExpect(status().isOk())
        .andExpect(content().json(
            "{\"name\":\"บริษัท ไอทีดี คอมพิวเตอร์ จำกัด\",\"taxId\":\"0105550123451\",\"vatRegistered\":true,\"taxInvoicePaper\":\"A4\","
                + "\"exchangeDays\":7,\"manualDiscountMaxPercent\":5,\"requireShift\":true,\"shiftVarianceLimit\":100,"
                + "\"shiftMaxHours\":12,\"staffSeeCost\":false}", true));
  }

  @Test
  void companySettingsNeedACompanyAdminAndApplyAtOnce() throws Exception {
    mvc.perform(as(staff, put("/settings/company"), companyInfo("ใหม่")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ข้อมูลร้านได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, put("/settings/company"), companyInfo("ใหม่")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"), true));
    mvc.perform(as(owner, put("/settings/company"), companyInfo(" ")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากรอกชื่อร้าน"), true));
    Map<String, Object> badHours = companyInfo("ร้าน");
    badHours.put("shiftMaxHours", 8.5);
    mvc.perform(as(owner, put("/settings/company"), badHours))
        .andExpect(content().json(message("ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48"), true));

    mvc.perform(as(owner, put("/settings/company"), companyInfo(" ร้านใหม่ ")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("ร้านใหม่"))
        .andExpect(jsonPath("$.taxId").value("0105550123451"))
        .andExpect(jsonPath("$.manualDiscountMaxPercent").value(2.5))
        .andExpect(jsonPath("$.staffSeeCost").value(true));
    // staffSeeCost มีผลกับ user ตั้งแต่ request ถัดไป (E43)
    mvc.perform(as(staff, get("/me"))).andExpect(jsonPath("$.canSeeCost").value(true))
        .andExpect(jsonPath("$.company.name").value("ร้านใหม่"));
    assertThat(companies.audit(c, "company_settings"))
        .containsExactly("company_settings|" + c.code() + "|ชื่อผู้ประกอบการ: บริษัท " + c.code() + " → ร้านใหม่"
            + " · เลขประจำตัวผู้เสียภาษี: - → 0105550123451 · กระดาษใบกำกับภาษี: A4 → 80 มม. · เปลี่ยนสินค้าได้ภายใน: 7 วัน → 3 วัน"
            + " · ส่วนลดพิเศษที่พนักงานให้ได้สูงสุด: 5% → 2.5% · ใช้ระบบกะ: เปิด → ปิด · ส่วนต่างที่ต้องให้ admin อนุมัติ: ฿100 → ฿50"
            + " · เตือนเมื่อกะเปิดนานเกิน: 12 ชม. → 10 ชม. · ผู้ใช้ทั่วไปเห็นทุน / กำไร: ปิด → เปิด"); // เฉพาะค่าที่เปลี่ยน (C3-5)
  }

  @Test
  void branchSettingsAreTheWorkingBranchsAndNeedAnAdminOfIt() throws Exception {
    mvc.perform(as(boss, get("/settings/branch")).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("HQ"));
    mvc.perform(as(boss, get("/settings/branch"))).andExpect(jsonPath("$.id").value(c.hq()));

    Map<String, Object> details = Map.of("name", " สาขาใหญ่ ", "address", "ที่อยู่ใหม่", "phone", "02", "receiptFooter", "ขอบคุณ");
    mvc.perform(as(staff, put("/settings/branch"), details).header("X-Branch-Id", c.hq()))
        .andExpect(status().isForbidden()).andExpect(content().json(message("แก้ข้อมูลร้านได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, put("/settings/branch"), details).header("X-Branch-Id", c.bn()))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ไม่มีสิทธิ์ทำงานในสาขานี้"), true));
    mvc.perform(as(boss, put("/settings/branch"), Map.of("address", " ")).header("X-Branch-Id", c.hq()))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("กรุณากรอกที่อยู่"), true));
    mvc.perform(as(boss, put("/settings/branch"), details).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("สาขาใหญ่"))
        .andExpect(jsonPath("$.code").value("HQ"))
        .andExpect(jsonPath("$.branchNo").value("00000"));
    assertThat(companies.audit(c, "branch_settings")).containsExactly("branch_settings|HQ|แก้ชื่อสถานประกอบการ / ที่อยู่ / โทร / ท้ายใบเสร็จ");
  }

  @Test
  void storeInfoIsCompanyPlusBranch() throws Exception {
    mvc.perform(as(staff, get("/settings/store")).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placeName").value("HQ"))
        .andExpect(jsonPath("$.branchType").value("head"))
        .andExpect(jsonPath("$.branchNo").value(""));
    // เอกสารของสาขาอื่น (E22) — สาขาใดก็ได้ของบริษัท, ของบริษัทอื่น = ไม่พบ
    mvc.perform(as(staff, get("/settings/store").param("branchId", Long.toString(c.bn()))))
        .andExpect(jsonPath("$.branchType").value("branch")).andExpect(jsonPath("$.branchNo").value("00001"));
    mvc.perform(as(staff, get("/settings/store").param("branchId", Long.toString(tenantB.ids().branchId()))))
        .andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบสาขา"), true));

    Map<String, Object> store = new HashMap<>(companyInfo("บริษัทใหม่"));
    store.putAll(Map.of("placeName", "ร้านหน้า", "branchType", "head", "branchNo", "", "address", "ถนน", "phone", "1", "receiptFooter", "ท้าย"));
    mvc.perform(as(boss, put("/settings/store"), store).header("X-Branch-Id", c.hq()))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"), true));
    mvc.perform(as(owner, put("/settings/store"), store).header("X-Branch-Id", c.bn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("บริษัทใหม่"))
        .andExpect(jsonPath("$.placeName").value("ร้านหน้า"))
        .andExpect(jsonPath("$.branchNo").value("00001"));
    mvc.perform(as(owner, get("/branches/" + c.bn()))).andExpect(jsonPath("$.name").value("ร้านหน้า")).andExpect(jsonPath("$.address").value("ถนน"));
  }

  @Test
  void menuAccessDefaultsSavesInMenuOrderAndNeedsAnAdmin() throws Exception {
    mvc.perform(as(staff, get("/settings/menu-access")))
        .andExpect(jsonPath("$.userMenus", Matchers.contains("dashboard", "pos", "sales", "settings")));
    mvc.perform(as(staff, put("/settings/menu-access"), Map.of("userMenus", List.of("pos"))))
        .andExpect(status().isForbidden()).andExpect(content().json(message("กำหนดสิทธิ์เมนูได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, put("/settings/menu-access"), Map.of("userMenus", List.of("users"))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เมนูผู้ใช้งาน / บัญชีบริษัท เป็นของผู้ดูแลระบบเท่านั้น"), true));
    mvc.perform(as(boss, put("/settings/menu-access"), Map.of("userMenus", List.of("sku", "pos", "inventory"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userMenus", Matchers.contains("pos", "inventory", "sku")));
    mvc.perform(as(staff, get("/settings/menu-access"))).andExpect(jsonPath("$.userMenus", Matchers.contains("pos", "inventory", "sku")));
    assertThat(companies.audit(c, "menu_access")).containsExactly("menu_access||ผู้ใช้ทั่วไปเห็น: ขายหน้าร้าน, คลังสินค้า, จัดการ SKU");
  }
}
