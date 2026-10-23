package com.an22.api.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.security.JwtService;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantB;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** ทุก request ของร้าน: token → ผู้ใช้ + บริษัทจาก DB, X-Branch-Id, รอลบ, me, auth/verify. */
class SessionIT extends ApiTest {

  private static final String EXPIRED = "{\"message\":\"เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่\"}";
  private static final String WRONG_LOGIN = "{\"message\":\"ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง\"}";

  @Autowired TestCompanies companies;
  @Autowired TenantB tenantB;
  @Autowired JwtService jwt;

  @Test
  void meAnswersTheTokensSession() throws Exception {
    String token = login("DEMO", "somchai", "1234");
    mvc.perform(get("/me").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.user.username").value("somchai"))
        .andExpect(jsonPath("$.company.code").value("DEMO"))
        .andExpect(jsonPath("$.branches[*].id", Matchers.contains(1)))
        .andExpect(jsonPath("$.billing").value((Object) null))
        .andExpect(jsonPath("$.canSeeCost").value(false));
  }

  @Test
  void missingUnknownOrPlatformTokenIs401() throws Exception {
    mvc.perform(get("/me")).andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    mvc.perform(get("/me").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    mvc.perform(get("/me").header("Authorization", bearer(jwt.issuePlatform(1))))
        .andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));
    // token ของบริษัทที่ไม่มี / ผู้ใช้ที่ไม่มี
    mvc.perform(get("/me").header("Authorization", bearer(jwt.issueShop(999_999, 1, "owner", true, List.of()))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/me").header("Authorization", bearer(jwt.issueShop(1, 999_999, "owner", true, List.of()))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenOfAnotherCompanysUserIsRefused() throws Exception {
    // tenant ของ token = DEMO แต่ userId เป็นของบริษัท B → ไม่พบใน DEMO (RLS + WHERE tenant)
    TenantB.Ids b = tenantB.ids();
    mvc.perform(get("/me").header("Authorization", bearer(jwt.issueShop(1, b.ownerId(), "owner", true, List.of()))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deactivatedUserOrSuspendedCompanyLosesTheSessionAtOnce() throws Exception {
    Company c = companies.create();
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    mvc.perform(get("/me").header("Authorization", bearer(staff))).andExpect(status().isOk());

    companies.setUserActive(c, c.staff(), false);
    mvc.perform(get("/me").header("Authorization", bearer(staff))).andExpect(status().isUnauthorized()).andExpect(content().json(EXPIRED, true));

    companies.suspend(c, "overdue");
    mvc.perform(get("/me").header("Authorization", bearer(owner))).andExpect(status().isUnauthorized());
  }

  @Test
  void branchHeaderMustBeAnActiveBranchTheUserMayUse() throws Exception {
    Company c = companies.create();
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    String forbidden = "{\"message\":\"ไม่มีสิทธิ์ทำงานในสาขานี้\"}";

    mvc.perform(get("/test-probe/session").header("Authorization", bearer(staff)).header("X-Branch-Id", c.hq()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.branchId").value(c.hq()));
    mvc.perform(get("/test-probe/session").header("Authorization", bearer(staff)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.branchId").value((Object) null));
    // สาขาที่ไม่มีสิทธิ์ / ปิดใช้งาน / ของบริษัทอื่น / อ่านไม่ได้
    for (Object branch : new Object[] {c.bn(), c.old(), tenantB.ids().branchId(), "abc"}) {
      mvc.perform(get("/test-probe/session").header("Authorization", bearer(staff)).header("X-Branch-Id", branch))
          .andExpect(status().isForbidden()).andExpect(content().json(forbidden, true));
    }
    mvc.perform(get("/test-probe/session").header("Authorization", bearer(owner)).header("X-Branch-Id", c.old()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/test-probe/session").header("Authorization", bearer(owner)).header("X-Branch-Id", c.bn()))
        .andExpect(status().isOk());
    // /me ไม่ตรวจ (สาขาที่เครื่องจำไว้อาจหมดสิทธิ์แล้ว)
    mvc.perform(get("/me").header("Authorization", bearer(staff)).header("X-Branch-Id", c.bn())).andExpect(status().isOk());
  }

  @Test
  void requestTransactionsRunAsTheTokensTenantAndUser() throws Exception {
    Company c = companies.create();
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    mvc.perform(get("/test-probe/session").header("Authorization", bearer(staff)).header("X-Terminal-Id", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(c.id()))
        .andExpect(jsonPath("$.terminalId").value(7))
        .andExpect(jsonPath("$.db.tenant").value(Long.toString(c.id())))
        .andExpect(jsonPath("$.db.user").value(Long.toString(c.staff())))
        .andExpect(jsonPath("$.db.kind").value("user"))
        .andExpect(jsonPath("$.db.users").value(4))
        .andExpect(jsonPath("$.db.companies").value(1));
  }

  @Test
  void deletionPendingAllowsOnlyMeCompanyAndAuditLog() throws Exception {
    Company c = companies.create();
    String owner = login(c.code(), "owner", TestCompanies.PASSWORD);
    companies.suspend(c, "deletion");
    mvc.perform(get("/test-probe/session").header("Authorization", bearer(owner)))
        .andExpect(status().isForbidden())
        .andExpect(content().json(
            "{\"message\":\"บริษัทอยู่ระหว่างรอลบข้อมูล — ดาวน์โหลดข้อมูลหรือยกเลิกคำขอลบได้ที่เมนูบัญชีบริษัท\"}", true));
    mvc.perform(get("/me").header("Authorization", bearer(owner))).andExpect(status().isOk());
    // company/* ผ่านด่านนี้
    mvc.perform(get("/company/deletion").header("Authorization", bearer(owner))).andExpect(status().isOk());
  }

  @Test
  void verifyChecksAUserOfTheTokensCompanyAndAlwaysAnswers400() throws Exception {
    Company c = companies.create();
    String staff = login(c.code(), "staff", TestCompanies.PASSWORD);
    String body = JSON.writeValueAsString(Map.of("username", "BOSS", "password", TestCompanies.PASSWORD));
    mvc.perform(post("/auth/verify").header("Authorization", bearer(staff)).header("X-Real-IP", "192.0.2.10")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value("boss"))
        .andExpect(jsonPath("$.user.role").value("admin"));

    for (Map<String, String> wrong : List.of(
        Map.of("username", "boss", "password", "x"),
        Map.of("username", "gone", "password", TestCompanies.PASSWORD),
        Map.of("username", "somchai", "password", "1234"))) {
      mvc.perform(post("/auth/verify").header("Authorization", bearer(staff)).header("X-Real-IP", "192.0.2.10")
              .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(wrong)))
          .andExpect(status().isBadRequest()).andExpect(content().json(WRONG_LOGIN, true));
    }
    // ไม่มี / ไม่รู้จัก token → 400 ไม่ใช่ 401 (ไม่ logout คนหน้าเครื่อง)
    mvc.perform(post("/auth/verify").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest()).andExpect(content().json(WRONG_LOGIN, true));
    mvc.perform(post("/auth/verify").header("Authorization", "Bearer nope").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest()).andExpect(content().json(WRONG_LOGIN, true));
    companies.setUserActive(c, c.staff(), false);
    mvc.perform(post("/auth/verify").header("Authorization", bearer(staff)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest()).andExpect(content().json(WRONG_LOGIN, true));
  }
}
