package com.an22.api.auth;

import com.an22.api.model.BranchPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * สมัครใช้งาน / ลิงก์ตั้งรหัสผ่าน (E37 / E41) — platformTx (ยังไม่มี session). ตารางของบริษัทใส่ {@code app_tenant_id()} เหมือน dao อื่น:
 * ผู้เรียกตั้ง {@code TxActor} ที่มีบริษัทแล้ว ({@code ActorDao.becomeOnPlatform}); อ่าน / แก้ระบุบริษัทเองเสมอ (BYPASSRLS).
 */
@Repository
class AccountDao {

  /** ลิงก์ที่ล็อกไว้ (FOR UPDATE — ใช้ได้ครั้งเดียวแม้กดพร้อมกัน). */
  record ResetLink(long id, long companyId, long userId, Instant expiresAt, boolean used) {}

  record Owner(long id, String email) {}

  private final JdbcClient jdbc;

  AccountDao(@Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.jdbc = platformJdbc;
  }

  /** รหัสที่เคยใช้ (รวมบริษัทที่ลบแล้ว — E39) = empty. */
  Optional<Long> insertCompany(String code, String planId, LocalDate trialEndsOn, LocalDate nextInvoiceOn) {
    return jdbc.sql("INSERT INTO dt_companies (code, plan_id, trial_ends_on, next_invoice_on) VALUES (:code, :plan, :trial, :next)"
            + " ON CONFLICT (code) DO NOTHING RETURNING id")
        .param("code", code).param("plan", planId).param("trial", trialEndsOn).param("next", nextInvoiceOn)
        .query(Long.class).optional();
  }

  boolean codeTaken(String code) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_companies WHERE code = :code)").param("code", code).query(Boolean.class).single();
  }

  long insertOwner(String username, String name, String email, String passwordHash) {
    return jdbc.sql("INSERT INTO dt_users (tenant_id, username, name, email, role, active, all_branches, password_hash)"
            + " VALUES (app_tenant_id(), :username, :name, :email, 'owner', true, true, :hash) RETURNING id")
        .param("username", username).param("name", name).param("email", email).param("hash", passwordHash)
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

  /** owner ที่ใช้งานอยู่ของบริษัทที่อีเมลตรง (ไม่สนตัวพิมพ์). */
  Optional<Owner> activeOwnerByEmail(long companyId, String emailLower) {
    return jdbc.sql("SELECT id, email FROM dt_users WHERE tenant_id = :t AND role = 'owner' AND active AND lower(email) = :email")
        .param("t", companyId).param("email", emailLower)
        .query((rs, i) -> new Owner(rs.getLong("id"), rs.getString("email")))
        .optional();
  }

  void insertResetToken(long companyId, long userId, byte[] tokenHash, Instant expiresAt) {
    jdbc.sql("INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at) VALUES (:c, :u, :hash, :expires)")
        .param("c", companyId).param("u", userId).param("hash", tokenHash).param("expires", Timestamp.from(expiresAt))
        .update();
  }

  Optional<ResetLink> lockResetToken(byte[] tokenHash) {
    return jdbc.sql("SELECT id, company_id, user_id, expires_at, used_at IS NOT NULL AS used FROM dt_password_reset_tokens"
            + " WHERE token_hash = :hash FOR UPDATE")
        .param("hash", tokenHash)
        .query((rs, i) -> new ResetLink(rs.getLong("id"), rs.getLong("company_id"), rs.getLong("user_id"),
            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("used")))
        .optional();
  }

  void useResetToken(long id, Instant at) {
    jdbc.sql("UPDATE dt_password_reset_tokens SET used_at = :at WHERE id = :id").param("at", Timestamp.from(at)).param("id", id).update();
  }

  void setPassword(long companyId, long userId, String passwordHash) {
    jdbc.sql("UPDATE dt_users SET password_hash = :hash WHERE tenant_id = :t AND id = :id")
        .param("hash", passwordHash).param("t", companyId).param("id", userId)
        .update();
  }
}
