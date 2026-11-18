package com.an22.api.rules;

import com.an22.api.model.BranchPayload;
import com.an22.api.model.CompanyInfo;
import com.an22.api.model.NewCompanyPayload;
import java.util.List;

/** port จาก {@code core/models/signup.model.ts} (E41 / E59): บริษัทใหม่ที่ platform สร้าง + ลิงก์ตั้งรหัสผ่าน owner. */
public final class SignupRules {

  /** {@code FORGOT_PASSWORD_ANSWER}: คำตอบเดียวเสมอ (ไม่บอกว่ามีรหัสบริษัท / อีเมลนี้ไหม). */
  public static final String FORGOT_PASSWORD_ANSWER =
      "ถ้ารหัสบริษัทและอีเมลตรงกับเจ้าของบริษัท ระบบจะส่งลิงก์ตั้งรหัสผ่านใหม่ไปที่อีเมลนั้น (ใช้ได้ภายใน 1 ชั่วโมง)";
  /** {@code RESET_LINK_MINUTES}. */
  public static final int RESET_LINK_MINUTES = 60;
  /** {@code SETUP_LINK_HOURS} (E59): ลิงก์ตั้งรหัสผ่านครั้งแรกจาก platform. */
  public static final int SETUP_LINK_HOURS = 72;
  /** {@code MAX_TRIAL_DAYS} (E59). */
  public static final int MAX_TRIAL_DAYS = 90;

  private SignupRules() {}

  /** {@code companyCodeError()}: A-Z 0-9 ยาว 3–20 (ตัวใหญ่แล้ว). */
  public static String companyCodeError(String code) {
    String value = CompanyRules.normalizeCompanyCode(code);
    if (value.isEmpty()) {
      return "กรุณากรอกรหัสบริษัท";
    }
    return value.matches("[A-Z0-9]{3,20}") ? null : "รหัสบริษัทใช้ได้เฉพาะ A-Z, 0-9 ยาว 3–20 ตัว";
  }

  /**
   * {@code newCompanyError()}: บริษัท → สาขาแรก → owner → แพ็กเกจ → วันทดลองใช้. {@code p} ผ่าน {@link #normalize} แล้ว; รหัสซ้ำเป็นของ
   * server ({@code รหัสบริษัทนี้ถูกใช้แล้ว}).
   */
  public static String newCompanyError(NewCompanyPayload p) {
    String company = companyCodeError(p.companyCode());
    if (company == null) {
      CompanyInfo d = CompanyRules.COMPANY_INFO_DEFAULTS;
      company = CompanyRules.companyInfoError(new CompanyInfo(p.companyName(), p.taxId().strip(), p.vatRegistered(), d.taxInvoicePaper(),
          d.exchangeDays(), d.manualDiscountMaxPercent(), d.requireShift(), d.shiftVarianceLimit(), d.shiftMaxHours(), d.staffSeeCost()));
    }
    if (company != null) {
      return company.replace("ชื่อร้าน", "ชื่อบริษัท");
    }
    BranchPayload b = p.branch();
    String branch = CompanyRules.branchError(null,
        new BranchPayload(b.code(), b.name(), b.branchNo(), b.address(), b.phone(), CompanyRules.DEFAULT_RECEIPT_FOOTER, true), List.of());
    if (branch != null) {
      return branch;
    }
    NewCompanyPayload.Owner o = p.owner();
    if (o.name().isBlank()) {
      return "กรุณากรอกชื่อเจ้าของบริษัท";
    }
    String owner = UserRules.userIdentityError(List.of(), o.username(), o.email(), "owner", null);
    if (owner != null) {
      return owner;
    }
    if (PlanRules.PLANS.stream().noneMatch(plan -> plan.id().equals(p.planId()))) {
      return "กรุณาเลือกแพ็กเกจ";
    }
    int days = p.trialDays();
    return days < 0 || days > MAX_TRIAL_DAYS ? "วันทดลองใช้ต้องเป็นจำนวนเต็ม 0–" + MAX_TRIAL_DAYS + " วัน" : null;
  }

  /**
   * ค่าขาเข้าแบบ mock: ไม่ส่ง = ว่าง, ชื่อบริษัท / เลขภาษีตัดช่องว่าง, vatRegistered {@code !!}, trialDays ไม่ส่ง = {@code TRIAL_DAYS}
   * (ทศนิยม = {@code IntegerOrInvalid.INVALID} → ข้อความของกฎ).
   */
  public static NewCompanyPayload normalize(NewCompanyPayload in) {
    BranchPayload b = in.branch() == null ? new BranchPayload(null, null, null, null, null, null, null) : in.branch();
    NewCompanyPayload.Owner o = in.owner() == null ? new NewCompanyPayload.Owner(null, null, null) : in.owner();
    return new NewCompanyPayload(
        str(in.companyCode()), str(in.companyName()).strip(), str(in.taxId()).strip(), Boolean.TRUE.equals(in.vatRegistered()),
        new BranchPayload(str(b.code()), str(b.name()), str(b.branchNo()), str(b.address()), str(b.phone()), null, true),
        new NewCompanyPayload.Owner(str(o.name()), str(o.username()), str(o.email())), str(in.planId()),
        in.trialDays() == null ? SubscriptionRules.TRIAL_DAYS : in.trialDays());
  }

  private static String str(String v) {
    return v == null ? "" : v;
  }
}
