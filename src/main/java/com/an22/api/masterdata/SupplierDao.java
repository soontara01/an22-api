package com.an22.api.masterdata;

import com.an22.api.model.Supplier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_suppliers ของบริษัทใน transaction — productCount = 0 จนกว่าจะมี products (ขั้น 5). */
@Repository
public class SupplierDao {

  private static final String COLUMNS =
      "id, code, name, tax_id, branch_type, branch_no, address, contact_name, phone, email, credit_days, bank_name,"
          + " bank_account_no, bank_account_name, note, active";
  private static final String SELECT = "SELECT " + COLUMNS + ", 0 AS product_count FROM dt_suppliers";

  private final JdbcClient jdbc;

  SupplierDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public List<Supplier> list() {
    return jdbc.sql(SELECT + " ORDER BY id").query(SupplierDao::row).list();
  }

  public Optional<Supplier> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(SupplierDao::row).optional();
  }

  public long insert(Supplier s) {
    return jdbc.sql(
            "INSERT INTO dt_suppliers (tenant_id, code, name, tax_id, branch_type, branch_no, address, contact_name, phone, email,"
                + " credit_days, bank_name, bank_account_no, bank_account_name, note, active) VALUES (app_tenant_id(), :code, :name,"
                + " :taxId, :branchType, :branchNo, :address, :contactName, :phone, :email, :creditDays, :bankName, :bankAccountNo,"
                + " :bankAccountName, :note, :active) RETURNING id")
        .params(params(s))
        .query(Long.class)
        .single();
  }

  public void update(long id, Supplier s) {
    jdbc.sql(
            "UPDATE dt_suppliers SET code = :code, name = :name, tax_id = :taxId, branch_type = :branchType,"
                + " branch_no = :branchNo, address = :address, contact_name = :contactName, phone = :phone, email = :email,"
                + " credit_days = :creditDays, bank_name = :bankName, bank_account_no = :bankAccountNo,"
                + " bank_account_name = :bankAccountName, note = :note, active = :active WHERE id = :id")
        .params(params(s))
        .param("id", id)
        .update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_suppliers WHERE id = :id").param("id", id).update();
  }

  private static Map<String, Object> params(Supplier s) {
    return Map.ofEntries(
        Map.entry("code", s.code()), Map.entry("name", s.name()), Map.entry("taxId", s.taxId()),
        Map.entry("branchType", s.branchType()), Map.entry("branchNo", s.branchNo()), Map.entry("address", s.address()),
        Map.entry("contactName", s.contactName()), Map.entry("phone", s.phone()), Map.entry("email", s.email()),
        Map.entry("creditDays", s.creditDays()), Map.entry("bankName", s.bankName()),
        Map.entry("bankAccountNo", s.bankAccountNo()), Map.entry("bankAccountName", s.bankAccountName()),
        Map.entry("note", s.note()), Map.entry("active", s.active()));
  }

  private static Supplier row(ResultSet rs, int i) throws SQLException {
    return new Supplier(
        rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("tax_id"), rs.getString("branch_type"),
        rs.getString("branch_no"), rs.getString("address"), rs.getString("contact_name"), rs.getString("phone"),
        rs.getString("email"), rs.getInt("credit_days"), rs.getString("bank_name"), rs.getString("bank_account_no"),
        rs.getString("bank_account_name"), rs.getString("note"), rs.getBoolean("active"), rs.getInt("product_count"));
  }
}
