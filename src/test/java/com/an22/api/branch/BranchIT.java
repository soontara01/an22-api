package com.an22.api.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantB;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code /branches} — สิทธิ์ (E6), branchError, แพ็กเกจ (E34), ข้าม tenant. */
class BranchIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantB tenantB;

  private Company c;
  private String owner;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
  }

  private static Map<String, Object> branch(String code, String no, boolean active) {
    return Map.of("code", code, "name", "สาขา " + code, "branchNo", no, "address", "ที่อยู่", "active", active, "id", 999);
  }

  @Test
  void everyoneReadsEveryBranchIncludingInactive() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(staff, get("/branches"))).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", Matchers.contains("HQ", "BN", "OLD")));
    mvc.perform(as(staff, get("/branches/" + c.old()))).andExpect(jsonPath("$.active").value(false));
    mvc.perform(as(staff, get("/branches/" + tenantB.ids().branchId())))
        .andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบสาขา"), true));
    mvc.perform(as(staff, get("/branches/abc"))).andExpect(status().isNotFound());
  }

  @Test
  void writesNeedACompanyAdmin() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String boss = login(c.code(), "boss", TestCompanies.PASSWORD);
    mvc.perform(as(staff, post("/branches"), branch("NEW", "00009", true)))
        .andExpect(status().isForbidden()).andExpect(content().json(message("จัดการสาขาได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(boss, put("/branches/" + c.hq()), Map.of("name", "x")))
        .andExpect(status().isForbidden()).andExpect(content().json(message("ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"), true));
  }

  @Test
  void createAndUpdateFollowBranchRules() throws Exception {
    mvc.perform(as(owner, post("/branches"), Map.of("code", " new1 ", "name", "สาขาใหม่", "branchNo", "00009", "address", "x")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("NEW1"))
        .andExpect(jsonPath("$.id").value(Matchers.not(999)))
        .andExpect(jsonPath("$.receiptFooter").value("ขอบคุณที่ใช้บริการ"))
        .andExpect(jsonPath("$.active").value(true));
    mvc.perform(as(owner, post("/branches"), branch("hq", "00008", true)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสสาขานี้มีอยู่แล้ว"), true));
    mvc.perform(as(owner, put("/branches/" + c.bn()), Map.of("branchNo", "00000")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("เลขที่สาขานี้มีอยู่แล้ว"), true));
    mvc.perform(as(owner, put("/branches/" + c.bn()), Map.of("active", false)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.code").value("BN"));
    assertThat(companies.audit(c, "branch_change"))
        .containsExactly("branch_change|NEW1|เพิ่มสาขา สาขาใหม่", "branch_change|BN|แก้สาขา BN (ปิดใช้งาน)");
  }

  @Test
  void lastActiveBranchStays() throws Exception {
    mvc.perform(as(owner, put("/branches/" + c.bn()), Map.of("active", false))).andExpect(status().isOk());
    mvc.perform(as(owner, put("/branches/" + c.hq()), Map.of("active", false)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ต้องมีสาขาที่ใช้งานอยู่อย่างน้อย 1 สาขา"), true));
  }

  @Test
  void packageLimitsActiveBranches() throws Exception {
    companies.setPlan(c, "standard");
    String limit = message("แพ็กเกจ Standard ใช้สาขาได้ 3 สาขา — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท");
    mvc.perform(as(owner, post("/branches"), branch("C3", "00003", true))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/branches"), branch("C4", "00004", true)))
        .andExpect(status().isForbidden()).andExpect(content().json(limit, true));
    // ปิดใช้งานไม่นับ, เปิดกลับนับ, แก้ที่ใช้งานอยู่แล้วไม่นับ
    mvc.perform(as(owner, post("/branches"), branch("C5", "00005", false))).andExpect(status().isOk());
    mvc.perform(as(owner, put("/branches/" + c.old()), Map.of("active", true)))
        .andExpect(status().isForbidden()).andExpect(content().json(limit, true));
    mvc.perform(as(owner, put("/branches/" + c.hq()), Map.of("phone", "02"))).andExpect(status().isOk());
  }

  @Test
  void anotherCompanysBranchIsNotFound() throws Exception {
    mvc.perform(as(owner, put("/branches/" + tenantB.ids().branchId()), Map.of("name", "x")))
        .andExpect(status().isNotFound()).andExpect(content().json(message("ไม่พบสาขา"), true));
  }
}
