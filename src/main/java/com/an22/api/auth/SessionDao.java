package com.an22.api.auth;

import com.an22.api.model.BillingInvoice;
import com.an22.api.model.Branch;
import com.an22.api.model.Subscription;
import com.an22.api.model.User;
import com.an22.api.user.UserDao;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * อ่านบริษัท / ผู้ใช้ / สาขา ของ session — SQL ระบุ tenant เองทุกคำสั่ง จึงใช้ได้ทั้ง appTx (RLS กรองซ้ำ) และ platformTx
 * (login ก่อนรู้บริษัท, BYPASSRLS): {@code Via.APP} / {@code Via.PLATFORM} ต้องตรงกับ transaction ของ service.
 */
@Repository
public class SessionDao {

  public enum Via {
    APP,
    PLATFORM
  }

  /** แถวบริษัท + ข้อมูลบริษัทที่ session ใช้. */
  public record CompanyRow(
      long id,
      String code,
      boolean active,
      String suspendedReason,
      String planId,
      LocalDate purgeAfter,
      Subscription subscription,
      String name,
      String taxId,
      boolean staffSeeCost) {}

  /** ผู้ใช้ + hash รหัสผ่าน (ไม่เคยออกนอก service). */
  public record UserRow(User user, String passwordHash) {}

  private static final String COMPANY =
      "SELECT c.id, c.code, c.active, c.suspended_reason, c.plan_id, c.purge_after, c.trial_ends_on, c.next_invoice_on,"
          + " i.name, i.tax_id, i.staff_see_cost"
          + " FROM dt_companies c JOIN dt_company_info i ON i.tenant_id = c.id WHERE c.deleted_at IS NULL";

  private static final String USER = UserDao.SELECT + " WHERE u.tenant_id = :tenant";

  private final JdbcClient app;
  private final JdbcClient platform;

  SessionDao(JdbcClient appJdbc, @Qualifier("platformJdbc") JdbcClient platformJdbc) {
    this.app = appJdbc;
    this.platform = platformJdbc;
  }

  private JdbcClient jdbc(Via via) {
    return via == Via.APP ? app : platform;
  }

  /** บริษัทที่ยังไม่ถูกลบตามรหัส (ตัวใหญ่แล้ว). */
  public Optional<CompanyRow> companyByCode(Via via, String code) {
    return jdbc(via).sql(COMPANY + " AND c.code = :code").param("code", code).query(SessionDao::company).optional();
  }

  public Optional<CompanyRow> company(Via via, long tenantId) {
    return jdbc(via).sql(COMPANY + " AND c.id = :id").param("id", tenantId).query(SessionDao::company).optional();
  }

  public Optional<UserRow> userById(Via via, long tenantId, long userId) {
    return jdbc(via).sql(USER + " AND u.id = :id").param("tenant", tenantId).param("id", userId).query(SessionDao::user).optional();
  }

  /** {@code username} ตัวเล็กแล้ว. */
  public Optional<UserRow> userByUsername(Via via, long tenantId, String username) {
    return jdbc(via).sql(USER + " AND u.username = :username")
        .param("tenant", tenantId)
        .param("username", username)
        .query(SessionDao::user)
        .optional();
  }

  /** ทุกสาขาของบริษัท (รวมที่ปิด) เรียงตาม id. */
  public List<Branch> branches(Via via, long tenantId) {
    return jdbc(via).sql(
            "SELECT id, code, name, branch_no, address, phone, receipt_footer, active FROM dt_branches"
                + " WHERE tenant_id = :tenant ORDER BY id")
        .param("tenant", tenantId)
        .query((rs, i) -> new Branch(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("branch_no"),
            rs.getString("address"), rs.getString("phone"), rs.getString("receipt_footer"), rs.getBoolean("active")))
        .list();
  }

  /** เครื่อง POS เลขนี้เป็นของบริษัทไหม (ใช้งานอยู่หรือไม่ก็ได้). */
  public boolean terminalExists(Via via, long tenantId, int terminalId) {
    return jdbc(via).sql("SELECT EXISTS (SELECT 1 FROM dt_pos_terminals WHERE tenant_id = :t AND id = :id)")
        .param("t", tenantId)
        .param("id", terminalId)
        .query(Boolean.class)
        .single();
  }

  public List<BillingInvoice> invoices(Via via, long tenantId) {
    return jdbc(via).sql(
            "SELECT id, company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status, paid_at"
                + " FROM dt_billing_invoices WHERE company_id = :tenant ORDER BY id")
        .param("tenant", tenantId)
        .query((rs, i) -> {
          Timestamp paid = rs.getTimestamp("paid_at");
          return new BillingInvoice(
              rs.getLong("id"), rs.getLong("company_id"), rs.getString("invoice_no"), rs.getString("plan_id"),
              rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
              rs.getBigDecimal("amount"), rs.getObject("issued_on", LocalDate.class),
              rs.getObject("due_date", LocalDate.class), rs.getString("status"), paid == null ? null : paid.toInstant());
        })
        .list();
  }

  private static CompanyRow company(ResultSet rs, int i) throws SQLException {
    return new CompanyRow(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getBoolean("active"),
        rs.getString("suspended_reason"),
        rs.getString("plan_id"),
        rs.getObject("purge_after", LocalDate.class),
        new Subscription(rs.getObject("trial_ends_on", LocalDate.class), rs.getObject("next_invoice_on", LocalDate.class)),
        rs.getString("name"),
        rs.getString("tax_id"),
        rs.getBoolean("staff_see_cost"));
  }

  private static UserRow user(ResultSet rs, int i) throws SQLException {
    return new UserRow(UserDao.row(rs, i), rs.getString("password_hash"));
  }
}
