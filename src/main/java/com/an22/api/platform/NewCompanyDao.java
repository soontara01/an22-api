package com.an22.api.platform;

import com.an22.api.model.BranchPayload;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * บริษัทใหม่ที่ platform สร้าง (E59, platformTx — เดิมคือ signup E37). ตารางของบริษัทใส่ {@code app_tenant_id()} เหมือน dao อื่น: ผู้เรียกตั้ง
 * {@code TxActor} ที่มีบริษัทแล้ว ({@code ActorDao.becomeOnPlatform}).
 */
@Repository
class NewCompanyDao {

  private final JdbcClient jdbc;

  NewCompanyDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /** รหัสที่เคยใช้ (รวมบริษัทที่ลบแล้ว — E39) = empty. {@code trialEndsOn} null = ไม่มีช่วงทดลอง. */
  Optional<Long> insertCompany(String code, String planId, LocalDate trialEndsOn, LocalDate nextInvoiceOn) {
    return jdbc.sql("INSERT INTO dt_companies (code, plan_id, trial_ends_on, next_invoice_on) VALUES (:code, :plan, CAST(:trial AS date), :next)"
            + " ON CONFLICT (code) DO NOTHING RETURNING id")
        .param("code", code).param("plan", planId).param("trial", trialEndsOn).param("next", nextInvoiceOn)
        .query(Long.class).optional();
  }

  boolean codeTaken(String code) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_companies WHERE code = :code)").param("code", code).query(Boolean.class).single();
  }

  /** owner ยังไม่มีรหัสผ่าน (null — login ไม่ผ่าน) จนกว่าจะใช้ลิงก์ตั้งรหัสผ่าน (E59). */
  long insertOwner(String username, String name, String email) {
    return jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, email, role, active, all_branches, password_hash)"
            + " VALUES (app_tenant_id(), :username, :name, :email, 'owner', true, true, NULL) RETURNING id")
        .param("username", username).param("name", name).param("email", email)
        .query(Long.class).single();
  }

  /** ค่าอื่นของ company_info = ค่าเริ่มของ column (= {@code COMPANY_INFO_DEFAULTS}). */
  void insertCompanyInfo(String name, String taxId, boolean vatRegistered) {
    jdbc.sql("INSERT INTO dt_company_info (tenant_id, name, tax_id, vat_registered) VALUES (app_tenant_id(), :name, :tax, :vat)")
        .param("name", name).param("tax", taxId).param("vat", vatRegistered)
        .update();
  }

  void insertBranch(BranchPayload b) {
    jdbc.sql("INSERT INTO dt_branches (tenant_id, code, name, branch_no, address, phone, receipt_footer, active)"
            + " VALUES (app_tenant_id(), :code, :name, :no, :address, :phone, :footer, true)")
        .param("code", b.code()).param("name", b.name()).param("no", b.branchNo()).param("address", b.address())
        .param("phone", b.phone()).param("footer", b.receiptFooter())
        .update();
  }

  /** ช่องทางเงินสดของ seed ({@code SEED_PAYMENT_METHODS[0]}) เป็นช่องทางเดียวของบริษัทใหม่. */
  void insertCashMethod() {
    jdbc.sql("INSERT INTO dt_payment_methods (tenant_id, code, name, type, sort_order, cash_rounding, close_check)"
            + " VALUES (app_tenant_id(), 'CASH', 'เงินสด', 'cash', 1, '0.25', 'count')")
        .update();
  }
}
