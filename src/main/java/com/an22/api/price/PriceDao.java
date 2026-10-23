package com.an22.api.price;

import com.an22.api.model.SkuPrice;
import com.an22.api.rules.PriceRules;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_sku_prices ของบริษัทใน transaction — วันที่ส่งออกเป็น 'YYYY-MM-DD'. */
@Repository
public class PriceDao {

  private static final String SELECT = "SELECT id, product_id, price, start_date, end_date, note FROM dt_sku_prices";

  private final JdbcClient jdbc;

  PriceDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** ทุกช่วง (หรือของ SKU เดียว) เรียงตาม id เหมือน mock. */
  public List<SkuPrice> list(Long productId) {
    return jdbc.sql(SELECT + " WHERE CAST(:product AS bigint) IS NULL OR product_id = :product ORDER BY id")
        .param("product", productId)
        .query(PriceDao::row)
        .list();
  }

  public Optional<SkuPrice> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(PriceDao::row).optional();
  }

  /** ช่วงของ SKU นี้ สำหรับ {@code findOverlap()}. */
  public List<PriceRules.Period> periods(long productId) {
    return list(productId).stream()
        .map(p -> new PriceRules.Period(p.id(), p.productId(), p.price(), p.startDate(), p.endDate()))
        .toList();
  }

  public long insert(SkuPrice p) {
    return jdbc.sql(
            "INSERT INTO dt_sku_prices (tenant_id, product_id, price, start_date, end_date, note)"
                + " VALUES (app_tenant_id(), :product, :price, :start, :end, :note) RETURNING id")
        .params(params(p))
        .query(Long.class)
        .single();
  }

  public void update(long id, SkuPrice p) {
    jdbc.sql("UPDATE dt_sku_prices SET product_id = :product, price = :price, start_date = :start, end_date = :end, note = :note"
            + " WHERE id = :id")
        .params(params(p))
        .param("id", id)
        .update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_sku_prices WHERE id = :id").param("id", id).update();
  }

  /** {@code currentPrice} ของ SKU = ราคาของช่วงที่มีผลวันนี้ ({@code effectivePrice()}) — ไม่มีช่วง = ไม่อยู่ใน map. */
  public Map<Long, Double> current(LocalDate today, Long productId) {
    Map<Long, Double> prices = new HashMap<>();
    jdbc.sql("SELECT product_id, price FROM dt_sku_prices WHERE start_date <= :today AND (end_date IS NULL OR end_date >= :today)"
            + " AND (CAST(:product AS bigint) IS NULL OR product_id = :product)")
        .param("today", today)
        .param("product", productId)
        .query((ResultSet rs) -> {
          prices.put(rs.getLong("product_id"), rs.getBigDecimal("price").doubleValue());
        });
    return prices;
  }

  private static Map<String, Object> params(SkuPrice p) {
    Map<String, Object> m = new HashMap<>();
    m.put("product", p.productId());
    m.put("price", p.price());
    m.put("start", LocalDate.parse(p.startDate()));
    m.put("end", p.endDate() == null ? null : LocalDate.parse(p.endDate()));
    m.put("note", p.note());
    return m;
  }

  private static SkuPrice row(ResultSet rs, int i) throws SQLException {
    Date end = rs.getDate("end_date");
    return new SkuPrice(rs.getLong("id"), rs.getLong("product_id"), rs.getBigDecimal("price").doubleValue(),
        rs.getDate("start_date").toLocalDate().toString(), end == null ? null : end.toLocalDate().toString(), rs.getString("note"));
  }
}
