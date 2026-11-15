package com.an22.api.company;

import com.an22.api.model.CompanyInfo;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_company_info ของบริษัทใน transaction (1 แถว / บริษัท). */
@Repository
public class CompanyInfoDao {

  private final JdbcClient jdbc;

  CompanyInfoDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public CompanyInfo get() {
    return jdbc.sql(
            "SELECT name, tax_id, vat_registered, tax_invoice_paper, exchange_days, manual_discount_max_percent,"
                + " require_shift, shift_variance_limit, shift_max_hours, staff_see_cost FROM dt_company_info")
        .query((rs, i) -> new CompanyInfo(
            rs.getString("name"),
            rs.getString("tax_id"),
            rs.getBoolean("vat_registered"),
            rs.getString("tax_invoice_paper"),
            BigDecimal.valueOf(rs.getInt("exchange_days")),
            plain(rs.getBigDecimal("manual_discount_max_percent")),
            rs.getBoolean("require_shift"),
            plain(rs.getBigDecimal("shift_variance_limit")),
            BigDecimal.valueOf(rs.getInt("shift_max_hours")),
            rs.getBoolean("staff_see_cost")))
        .single();
  }

  /** ค่าที่ผ่าน {@code companyInfoError()} แล้ว. */
  public void update(CompanyInfo c) {
    jdbc.sql(
            "UPDATE dt_company_info SET name = :name, tax_id = :tax, vat_registered = :vat, tax_invoice_paper = :paper,"
                + " exchange_days = :days, manual_discount_max_percent = :pct, require_shift = :shift,"
                + " shift_variance_limit = :variance, shift_max_hours = :hours, staff_see_cost = :cost")
        .param("name", c.name())
        .param("tax", c.taxId())
        .param("vat", c.vatRegistered())
        .param("paper", c.taxInvoicePaper())
        .param("days", c.exchangeDays().intValueExact())
        .param("pct", c.manualDiscountMaxPercent())
        .param("shift", c.requireShift())
        .param("variance", c.shiftVarianceLimit())
        .param("hours", c.shiftMaxHours().intValueExact())
        .param("cost", c.staffSeeCost())
        .update();
  }

  /** {@code 5.00} → {@code 5}, {@code 2.50} → {@code 2.5} (ตอบเหมือนตัวเลขของ JS). */
  private static BigDecimal plain(BigDecimal value) {
    return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
  }
}
