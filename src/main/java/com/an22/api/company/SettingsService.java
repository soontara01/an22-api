package com.an22.api.company;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.MenuAccess;
import com.an22.api.model.StoreInfo;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.CompanyRules;
import com.an22.api.db.TenantLocks;
import com.an22.api.rules.ShiftRules;
import com.an22.api.shift.ShiftDao;
import com.an22.api.rules.MenuAccessRules;
import com.an22.api.rules.StoreInfoRules;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code /settings/*} (E11): ข้อมูลบริษัท, สาขาที่ทำงาน, StoreInfo (ช่วงเปลี่ยนผ่าน), สิทธิ์เมนูของ role user. */
@Service
public class SettingsService {

  static final String BRANCH_NOT_FOUND = "ไม่พบสาขา";

  private final CompanyInfoDao company;
  private final MenuAccessDao menus;
  private final BranchDao branches;
  private final Audit audit;
  private final ShiftDao shifts;
  private final TenantLocks locks;

  SettingsService(CompanyInfoDao company, MenuAccessDao menus, BranchDao branches, Audit audit, ShiftDao shifts, TenantLocks locks) {
    this.shifts = shifts;
    this.locks = locks;
    this.company = company;
    this.menus = menus;
    this.branches = branches;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public CompanyInfo companyInfo() {
    return company.get();
  }

  /** {@code PUT /settings/company} (admin บริษัท — controller ตรวจ): ปิดระบบกะได้เมื่อไม่มีกะเปิด ({@code requireShiftChangeError()}). */
  @Transactional
  public CompanyInfo updateCompanyInfo(ShopSession s, CompanyInfo in) {
    CompanyInfo info = CompanyRules.normalizeCompanyInfo(in);
    String problem = CompanyRules.companyInfoError(info);
    if (problem == null) {
      problem = requireShiftProblem(info.requireShift());
    }
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    CompanyInfo before = company.get();
    company.update(info);
    CompanyInfo after = company.get();
    recordCompanySettings(s, before, after);
    return after;
  }

  /**
   * {@code GET /settings/store}: สาขาของ X-Branch-Id (ไม่ส่ง = สาขาแรก) หรือ {@code ?branchId=} = สาขาของเอกสารที่พิมพ์ (E22, สาขาใดก็ได้ของ
   * บริษัท; ไม่มี → 404).
   */
  @Transactional(readOnly = true)
  public StoreInfo storeInfo(BranchContext ctx, String branchIdParam) {
    Branch branch;
    if (branchIdParam == null || branchIdParam.isEmpty()) {
      branch = requestBranch(ctx);
    } else {
      branch = branches.list().stream()
          .filter(b -> Long.toString(b.id()).equals(branchIdParam))
          .findFirst()
          .orElseThrow(() -> ApiException.notFound(BRANCH_NOT_FOUND));
    }
    return CompanyRules.storeInfoOf(company.get(), branch);
  }

  /** {@code PUT /settings/store} (ช่วงเปลี่ยนผ่าน, admin บริษัท): เขียนลงบริษัท + สาขาที่ทำงาน (เลขสาขาไม่เปลี่ยน). */
  @Transactional
  public StoreInfo updateStoreInfo(ShopSession s, BranchContext ctx, StoreInfo in) {
    StoreInfo payload = StoreInfoRules.normalizeStoreInfo(in);
    String problem = StoreInfoRules.storeInfoError(payload);
    if (problem == null) {
      problem = requireShiftProblem(payload.requireShift());
    }
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    CompanyInfo info = CompanyRules.companyInfoOf(payload);
    CompanyInfo before = company.get();
    company.update(info);
    Branch branch = requestBranch(ctx);
    BranchPayload details =
        new BranchPayload(
            branch.code(), payload.placeName().isEmpty() ? branch.name() : payload.placeName(), branch.branchNo(),
            payload.address(), payload.phone(), payload.receiptFooter(), branch.active());
    Branch saved = branches.update(branch.id(), details);
    CompanyInfo after = company.get();
    recordCompanySettings(s, before, after);
    return CompanyRules.storeInfoOf(after, saved);
  }

  /** {@code requireShiftProblem()} ของ mock: ปิดระบบกะขณะมีกะเปิดไม่ได้ — ล็อก "shifts" กันเปิดกะพร้อมกัน. */
  private String requireShiftProblem(boolean next) {
    boolean current = Boolean.TRUE.equals(company.get().requireShift());
    if (!current || next) {
      return null;
    }
    locks.lock(TenantLocks.SHIFTS);
    return ShiftRules.requireShiftChangeError(true, false, shifts.openCount());
  }

  @Transactional(readOnly = true)
  public Branch branchSettings(BranchContext ctx) {
    return requestBranch(ctx);
  }

  /**
   * {@code PUT /settings/branch}: ชื่อสถานประกอบการ / ที่อยู่ / โทร / ท้ายใบเสร็จ ของสาขาที่ทำงาน — admin ที่ทำงานในสาขานั้นได้ (ไม่ส่ง
   * X-Branch-Id = สาขาแรก ซึ่งต้องเป็นสาขาที่ทำงานได้ด้วย ไม่งั้น 403).
   */
  @Transactional
  public Branch updateBranchSettings(ShopSession s, BranchContext ctx, BranchPayload in) {
    Branch branch = requestBranch(ctx);
    if (s.branches().stream().noneMatch(b -> b.id() == branch.id())) {
      throw ApiException.forbidden("ไม่มีสิทธิ์ทำงานในสาขานี้");
    }
    BranchPayload current = CompanyRules.payloadOf(branch);
    BranchPayload details =
        CompanyRules.normalizeBranch(current, new BranchPayload(null, in.name(), null, in.address(), in.phone(), in.receiptFooter(), null));
    String problem = CompanyRules.branchDetailsError(details.name(), details.address(), details.receiptFooter());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    Branch saved = branches.update(branch.id(), details);
    audit.record(s, "branch_settings", saved.id(), "branch", saved.id(), saved.code(),
        "แก้ชื่อสถานประกอบการ / ที่อยู่ / โทร / ท้ายใบเสร็จ");
    return saved;
  }

  @Transactional(readOnly = true)
  public MenuAccess menuAccess() {
    return menus.get();
  }

  /** {@code PUT /settings/menu-access} (admin): เก็บตามลำดับเมนู ไม่ว่าส่งมาลำดับไหน. */
  @Transactional
  public MenuAccess updateMenuAccess(ShopSession s, MenuAccess in) {
    List<String> known = List.copyOf(MenuAccessRules.MENU.keySet());
    String problem = MenuAccessRules.menuAccessError(in, known);
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    List<String> ordered = known.stream().filter(in.userMenus()::contains).toList();
    menus.save(ordered);
    audit.record(s, "menu_access", null, null, null, null,
        "ผู้ใช้ทั่วไปเห็น: " + String.join(", ", ordered.stream().map(MenuAccessRules.MENU::get).toList()));
    return new MenuAccess(ordered);
  }

  /** สาขาของ request: X-Branch-Id (ตรวจแล้ว) หรือสาขาแรก. */
  private Branch requestBranch(BranchContext ctx) {
    if (ctx.branchId() != null) {
      return branches.find(ctx.branchId()).orElseThrow(() -> ApiException.notFound(BRANCH_NOT_FOUND));
    }
    return branches.first().orElseThrow(() -> ApiException.notFound(BRANCH_NOT_FOUND));
  }

  private void recordCompanySettings(ShopSession s, CompanyInfo before, CompanyInfo after) {
    audit.record(s, "company_settings", null, "company", s.tenantId(), s.companyCode(),
        AuditRules.companySettingsChanges(before, after));
  }
}
