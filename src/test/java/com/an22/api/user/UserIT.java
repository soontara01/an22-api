package com.an22.api.user;

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

/** {@code /users} — admin เท่านั้น, scope ของ admin สาขา (E6), กฎผู้ใช้ / owner, แพ็กเกจ, ข้าม tenant. */
class UserIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantB tenantB;

  private Company c;
  private String owner;
  private String boss;

  @BeforeEach
  void company() throws Exception {
    c = companies.create();
    owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    boss = login(c.code(), "boss", TestCompanies.PASSWORD);
  }

  private Map<String, Object> newUser(String username, List<Long> branchIds) {
    Map<String, Object> m = new HashMap<>();
    m.put("username", username);
    m.put("name", "ชื่อ " + username);
    m.put("email", "");
    m.put("role", "user");
    m.put("active", true);
    m.put("branchIds", branchIds);
    m.put("allBranches", false);
    m.put("password", "pass1");
    return m;
  }

  @Test
  void onlyAdminsManageUsers() throws Exception {
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(as(staff, get("/users"))).andExpect(status().isForbidden())
        .andExpect(content().json(message("จัดการผู้ใช้ได้เฉพาะผู้ดูแลระบบ"), true));
    mvc.perform(as(owner, get("/users"))).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].username", Matchers.contains("owner", "boss", "staff", "gone")))
        .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$[0].password").doesNotExist());
  }

  @Test
  void createdUserCanLogIn() throws Exception {
    Map<String, Object> body = newUser(" New.User ", List.of(c.bn(), c.hq(), c.hq()));
    mvc.perform(as(owner, post("/users"), body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("new.user"))
        .andExpect(jsonPath("$.branchIds", Matchers.contains((int) c.hq(), (int) c.bn())))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.password").doesNotExist());
    login(c.code(), "new.user", "pass1");
    assertThat(companies.audit(c, "user_change"))
        .containsExactly("user_change|new.user|เพิ่มผู้ใช้ — ผู้ใช้ทั่วไป · สาขา " + c.hq() + ", " + c.bn());
  }

  @Test
  void createChecksTheRulesInOrder() throws Exception {
    Map<String, Object> dup = newUser("STAFF", List.of(c.hq()));
    mvc.perform(as(owner, post("/users"), dup)).andExpect(status().isBadRequest())
        .andExpect(content().json(message("ชื่อผู้ใช้นี้มีอยู่แล้ว"), true));
    Map<String, Object> asOwner = newUser("x2x", List.of(c.hq()));
    asOwner.put("role", "owner");
    mvc.perform(as(owner, post("/users"), asOwner))
        .andExpect(content().json(message("ตั้งเจ้าของบริษัทได้ด้วยการโอนความเป็นเจ้าของเท่านั้น"), true));
    mvc.perform(as(owner, post("/users"), newUser("x3x", List.of(c.old()))))
        .andExpect(content().json(message("สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว"), true));
    Map<String, Object> shortPass = newUser("x4x", List.of(c.hq()));
    shortPass.put("password", "12");
    mvc.perform(as(owner, post("/users"), shortPass)).andExpect(content().json(message("รหัสผ่านต้องมีอย่างน้อย 4 ตัว"), true));
    // admin สาขาให้สาขาที่ตัวเองไม่มีไม่ได้ (E6) — 403
    mvc.perform(as(boss, post("/users"), newUser("x5x", List.of(c.bn()))))
        .andExpect(status().isForbidden())
        .andExpect(content().json(message("ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท"), true));
    mvc.perform(as(boss, post("/users"), newUser("x6x", List.of(c.hq())))).andExpect(status().isOk());
  }

  @Test
  void packageLimitsActiveUsers() throws Exception {
    companies.setPlan(c, "starter");
    // owner + boss + staff ใช้งานอยู่ = 3 (gone ปิด) → เพิ่มได้อีก 2
    mvc.perform(as(owner, post("/users"), newUser("p1p", List.of(c.hq())))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/users"), newUser("p2p", List.of(c.hq())))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/users"), newUser("p3p", List.of(c.hq()))))
        .andExpect(status().isForbidden())
        .andExpect(content().json(message("แพ็กเกจ Starter ใช้ผู้ใช้ที่ใช้งานได้ 5 คน — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"), true));
    mvc.perform(as(owner, put("/users/" + c.gone()), Map.of("active", true))).andExpect(status().isForbidden());
  }

  @Test
  void updateMergesAndGuardsAdminsAndTheOwner() throws Exception {
    mvc.perform(as(owner, put("/users/" + c.staff()), Map.of("name", "พนักงาน")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("พนักงาน")).andExpect(jsonPath("$.username").value("staff"));
    mvc.perform(as(owner, put("/users/" + c.owner()), Map.of("active", false)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ปิดใช้งานบัญชีของตัวเองไม่ได้"), true));
    mvc.perform(as(boss, put("/users/" + c.boss()), Map.of("role", "user")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ลดสิทธิ์ผู้ดูแลระบบของตัวเองไม่ได้"), true));
    String admin = setUpCompanyAdmin();
    mvc.perform(as(admin, put("/users/" + c.owner()), Map.of("role", "admin")))
        .andExpect(content().json(message("เปลี่ยนสิทธิ์ของเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน"), true));
    // admin สาขาแก้ผู้ใช้ทุกสาขาไม่ได้
    mvc.perform(as(boss, put("/users/" + c.owner()), Map.of("name", "x")))
        .andExpect(status().isForbidden())
        .andExpect(content().json(message("จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท"), true));
  }

  /** admin ทุกสาขาคนที่สอง → token. */
  private String setUpCompanyAdmin() throws Exception {
    Map<String, Object> body = newUser("chief", List.of());
    body.put("role", "admin");
    body.put("allBranches", true);
    mvc.perform(as(owner, post("/users"), body)).andExpect(status().isOk());
    return login(c.code(), "chief", "pass1");
  }

  @Test
  void deleteAndPassword() throws Exception {
    mvc.perform(as(owner, delete("/users/" + c.owner())))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ลบบัญชีของตัวเองไม่ได้"), true));
    String admin = setUpCompanyAdmin();
    mvc.perform(as(admin, delete("/users/" + c.owner())))
        .andExpect(content().json(message("ลบเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน"), true));

    mvc.perform(as(owner, put("/users/" + c.staff() + "/password"), Map.of("password", " x ")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านต้องไม่ขึ้นต้นหรือลงท้ายด้วยช่องว่าง"), true));
    mvc.perform(as(owner, put("/users/" + c.staff() + "/password"), Map.of("password", "newpass"))).andExpect(status().isOk());
    String staff = login(c.code(), "staff", "newpass");

    mvc.perform(as(owner, delete("/users/" + c.staff()))).andExpect(status().isOk());
    mvc.perform(as(staff, get("/me"))).andExpect(status().isUnauthorized());
    mvc.perform(as(owner, get("/users/" + c.staff()))).andExpect(status().isNotFound())
        .andExpect(content().json(message("ไม่พบผู้ใช้"), true));
    assertThat(companies.audit(c, "user_password")).containsExactly("user_password|staff|ผู้ดูแลระบบตั้งรหัสผ่านใหม่ให้");
    assertThat(companies.audit(c, "user_change")).contains("user_change|staff|ลบผู้ใช้");
  }

  @Test
  void anotherCompanysUserIsNotFound() throws Exception {
    long other = tenantB.ids().ownerId();
    mvc.perform(as(owner, get("/users/" + other))).andExpect(status().isNotFound());
    mvc.perform(as(owner, put("/users/" + other), Map.of("name", "x"))).andExpect(status().isNotFound());
    mvc.perform(as(owner, put("/users/" + other + "/password"), Map.of("password", "abcd"))).andExpect(status().isNotFound());
    mvc.perform(as(owner, delete("/users/" + other))).andExpect(status().isNotFound());
    // ให้สิทธิ์สาขาของบริษัทอื่นไม่ได้
    mvc.perform(as(owner, post("/users"), newUser("cross", List.of(tenantB.ids().branchId()))))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว"), true));
  }

  @Test
  void ownershipHandsOverOnlyFromTheOwnerWithTheirPassword() throws Exception {
    mvc.perform(as(boss, post("/company/owner"), Map.of("userId", c.owner(), "password", TestCompanies.PASSWORD)))
        .andExpect(status().isForbidden()).andExpect(content().json(message("โอนความเป็นเจ้าของได้เฉพาะเจ้าของบริษัท"), true));
    mvc.perform(as(owner, post("/company/owner"), Map.of("userId", c.boss(), "password", TestCompanies.PASSWORD)))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("ผู้รับโอนต้องมีอีเมล — แก้ข้อมูลผู้ใช้ให้มีอีเมลก่อน"), true));
    mvc.perform(as(owner, put("/users/" + c.boss()), Map.of("email", "boss@x.test"))).andExpect(status().isOk());
    mvc.perform(as(owner, post("/company/owner"), Map.of("userId", c.boss(), "password", "wrong")))
        .andExpect(status().isBadRequest()).andExpect(content().json(message("รหัสผ่านไม่ถูกต้อง"), true));

    mvc.perform(as(owner, post("/company/owner"), Map.of("userId", c.boss(), "password", TestCompanies.PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.username == 'boss')].role", Matchers.contains("owner")))
        .andExpect(jsonPath("$[?(@.username == 'boss')].allBranches", Matchers.contains(true)))
        .andExpect(jsonPath("$[?(@.username == 'owner')].role", Matchers.contains("admin")));
    mvc.perform(as(owner, get("/me"))).andExpect(jsonPath("$.user.role").value("admin"));
    assertThat(companies.audit(c, "owner_transfer")).containsExactly("owner_transfer|boss|โอนความเป็นเจ้าของให้ boss");
  }
}
