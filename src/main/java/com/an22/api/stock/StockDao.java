package com.an22.api.stock;

import com.an22.api.model.StockMovement;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_branch_stock (ล็อกแถว) + dt_stock_movements (append-only) ของบริษัทใน transaction. */
@Repository
public class StockDao {

  /** ยอดของ (สาขา, SKU) ที่ล็อกไว้. */
  public record Balance(int stock, double avgCost) {}

  private final JdbcClient jdbc;

  StockDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** สร้างแถวถ้ายังไม่มี (= 0) แล้ว {@code SELECT … FOR UPDATE} — ถือล็อกจน commit. */
  public Balance lock(long branchId, long productId) {
    jdbc.sql(
            "INSERT INTO dt_branch_stock (tenant_id, branch_id, product_id) VALUES (app_tenant_id(), :branch, :product)"
                + " ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING")
        .param("branch", branchId).param("product", productId)
        .update();
    return jdbc.sql("SELECT stock, avg_cost FROM dt_branch_stock WHERE branch_id = :branch AND product_id = :product FOR UPDATE")
        .param("branch", branchId).param("product", productId)
        .query((rs, i) -> new Balance(rs.getInt("stock"), rs.getBigDecimal("avg_cost").doubleValue()))
        .single();
  }

  public void set(long branchId, long productId, int stock, double avgCost) {
    jdbc.sql("UPDATE dt_branch_stock SET stock = :stock, avg_cost = :avg WHERE branch_id = :branch AND product_id = :product")
        .param("stock", stock).param("avg", BigDecimal.valueOf(avgCost)).param("branch", branchId).param("product", productId)
        .update();
  }

  public void insertMovement(long branchId, long productId, String type, int qty, double unitCost, double totalCost, int balanceQty,
      double balanceAvgCost, double balanceValue, List<String> serials, String note) {
    jdbc.sql(
            "INSERT INTO dt_stock_movements (tenant_id, branch_id, product_id, type, qty, unit_cost, total_cost, balance_qty,"
                + " balance_avg_cost, balance_value, serials, note) VALUES (app_tenant_id(), :branch, :product, :type, :qty, :unitCost,"
                + " :totalCost, :balanceQty, :balanceAvg, :balanceValue, :serials, :note)")
        .param("branch", branchId).param("product", productId).param("type", type).param("qty", qty)
        .param("unitCost", BigDecimal.valueOf(unitCost)).param("totalCost", BigDecimal.valueOf(totalCost))
        .param("balanceQty", balanceQty).param("balanceAvg", BigDecimal.valueOf(balanceAvgCost))
        .param("balanceValue", BigDecimal.valueOf(balanceValue)).param("serials", serials.toArray(String[]::new))
        .param("note", note)
        .update();
  }

  /** บัตรสต็อกของ (สาขา, SKU) เก่าก่อน. */
  public List<StockMovement> movements(long branchId, long productId) {
    return jdbc.sql(
            "SELECT id, product_id, branch_id, created_at, type, qty, unit_cost, total_cost, balance_qty, balance_avg_cost,"
                + " balance_value, serials, note FROM dt_stock_movements WHERE branch_id = :branch AND product_id = :product"
                + " ORDER BY created_at, id")
        .param("branch", branchId).param("product", productId)
        .query(StockDao::movement)
        .list();
  }

  private static StockMovement movement(ResultSet rs, int i) throws SQLException {
    return new StockMovement(
        rs.getLong("id"), rs.getLong("product_id"), rs.getLong("branch_id"), rs.getTimestamp("created_at").toInstant(),
        rs.getString("type"), rs.getInt("qty"), rs.getBigDecimal("unit_cost").doubleValue(),
        rs.getBigDecimal("total_cost").doubleValue(), rs.getInt("balance_qty"), rs.getBigDecimal("balance_avg_cost").doubleValue(),
        rs.getBigDecimal("balance_value").doubleValue(), List.of((String[]) rs.getArray("serials").getArray()), rs.getString("note"));
  }
}
