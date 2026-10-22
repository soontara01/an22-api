package com.an22.api.branch;

import com.an22.api.model.Branch;
import com.an22.api.model.BranchPayload;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_branches ของบริษัทใน transaction (RLS) — insert ใช้ {@code app_tenant_id()} (tenant มาจาก token เท่านั้น). */
@Repository
public class BranchDao {

  private static final String SELECT =
      "SELECT id, code, name, branch_no, address, phone, receipt_footer, active FROM dt_branches";

  private final JdbcClient jdbc;

  BranchDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** ทุกสาขา (รวมที่ปิด) เรียงตาม id. */
  public List<Branch> list() {
    return jdbc.sql(SELECT + " ORDER BY id").query(BranchDao::row).list();
  }

  public Optional<Branch> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(BranchDao::row).optional();
  }

  /** สาขาแรก (id น้อยสุด) — ค่าเริ่มเมื่อไม่ส่ง X-Branch-Id (E2). */
  public Optional<Branch> first() {
    return jdbc.sql(SELECT + " ORDER BY id LIMIT 1").query(BranchDao::row).optional();
  }

  public Branch insert(BranchPayload b) {
    return jdbc.sql(
            "INSERT INTO dt_branches (tenant_id, code, name, branch_no, address, phone, receipt_footer, active)"
                + " VALUES (app_tenant_id(), :code, :name, :no, :address, :phone, :footer, :active)"
                + " RETURNING id, code, name, branch_no, address, phone, receipt_footer, active")
        .params(params(b))
        .query(BranchDao::row)
        .single();
  }

  public Branch update(long id, BranchPayload b) {
    return jdbc.sql(
            "UPDATE dt_branches SET code = :code, name = :name, branch_no = :no, address = :address, phone = :phone,"
                + " receipt_footer = :footer, active = :active WHERE id = :id"
                + " RETURNING id, code, name, branch_no, address, phone, receipt_footer, active")
        .params(params(b))
        .param("id", id)
        .query(BranchDao::row)
        .single();
  }

  private static Map<String, Object> params(BranchPayload b) {
    return Map.of(
        "code", b.code(), "name", b.name(), "no", b.branchNo(), "address", b.address(), "phone", b.phone(),
        "footer", b.receiptFooter(), "active", b.active());
  }

  public static Branch row(ResultSet rs, int i) throws SQLException {
    return new Branch(
        rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("branch_no"),
        rs.getString("address"), rs.getString("phone"), rs.getString("receipt_footer"), rs.getBoolean("active"));
  }
}
