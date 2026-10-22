package com.an22.api.masterdata;

import com.an22.api.model.PaymentMethod;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_payment_methods ของบริษัทใน transaction — รายการเรียงตาม sort_order (ปุ่มของ POS). */
@Repository
public class PaymentMethodDao {

  private static final String SELECT =
      "SELECT id, code, name, type, active, sort_order, require_reference, reference_label, min_amount, max_amount, fee_percent,"
          + " cash_rounding, prompt_pay_id, bank_account, installment_months, close_check, note FROM dt_payment_methods";

  private final JdbcClient jdbc;

  PaymentMethodDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public List<PaymentMethod> list() {
    return jdbc.sql(SELECT + " ORDER BY sort_order, id").query(PaymentMethodDao::row).list();
  }

  public Optional<PaymentMethod> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(PaymentMethodDao::row).optional();
  }

  /** ปุ่มใหม่อยู่ท้ายสุด: sort_order = max + 1. */
  public long insert(PaymentMethod m) {
    return jdbc.sql(
            "INSERT INTO dt_payment_methods (tenant_id, code, name, type, active, sort_order, require_reference, reference_label,"
                + " min_amount, max_amount, fee_percent, cash_rounding, prompt_pay_id, bank_account, installment_months,"
                + " close_check, note) VALUES (app_tenant_id(), :code, :name, :type, :active,"
                + " (SELECT coalesce(max(sort_order), 0) + 1 FROM dt_payment_methods), :requireReference, :referenceLabel,"
                + " :minAmount, :maxAmount, :feePercent, :cashRounding, :promptPayId, :bankAccount, :months, :closeCheck, :note)"
                + " RETURNING id")
        .params(params(m))
        .query(Long.class)
        .single();
  }

  /** ไม่แตะ sort_order (เปลี่ยนที่ {@code PUT /payment-methods/order} เท่านั้น). */
  public void update(long id, PaymentMethod m) {
    jdbc.sql(
            "UPDATE dt_payment_methods SET code = :code, name = :name, type = :type, active = :active,"
                + " require_reference = :requireReference, reference_label = :referenceLabel, min_amount = :minAmount,"
                + " max_amount = :maxAmount, fee_percent = :feePercent, cash_rounding = :cashRounding,"
                + " prompt_pay_id = :promptPayId, bank_account = :bankAccount, installment_months = :months,"
                + " close_check = :closeCheck, note = :note WHERE id = :id")
        .params(params(m))
        .param("id", id)
        .update();
  }

  public void setSortOrder(long id, int sortOrder) {
    jdbc.sql("UPDATE dt_payment_methods SET sort_order = :o WHERE id = :id").param("o", sortOrder).param("id", id).update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_payment_methods WHERE id = :id").param("id", id).update();
  }

  private static Map<String, Object> params(PaymentMethod m) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", m.code());
    p.put("name", m.name());
    p.put("type", m.type());
    p.put("active", m.active());
    p.put("requireReference", m.requireReference());
    p.put("referenceLabel", m.referenceLabel());
    p.put("minAmount", BigDecimal.valueOf(m.minAmount()));
    p.put("maxAmount", m.maxAmount() == null ? null : BigDecimal.valueOf(m.maxAmount()));
    p.put("feePercent", BigDecimal.valueOf(m.feePercent()));
    p.put("cashRounding", m.cashRounding());
    p.put("promptPayId", m.promptPayId());
    p.put("bankAccount", m.bankAccount());
    p.put("months", m.installmentMonths().toArray(Integer[]::new));
    p.put("closeCheck", m.closeCheck());
    p.put("note", m.note());
    return p;
  }

  private static PaymentMethod row(ResultSet rs, int i) throws SQLException {
    BigDecimal max = rs.getBigDecimal("max_amount");
    return new PaymentMethod(
        rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("type"), rs.getBoolean("active"),
        rs.getInt("sort_order"), rs.getBoolean("require_reference"), rs.getString("reference_label"),
        rs.getBigDecimal("min_amount").doubleValue(), max == null ? null : max.doubleValue(),
        rs.getBigDecimal("fee_percent").doubleValue(), rs.getString("cash_rounding"), rs.getString("prompt_pay_id"),
        rs.getString("bank_account"), Arrays.asList((Integer[]) rs.getArray("installment_months").getArray()),
        rs.getString("close_check"), rs.getString("note"));
  }
}
