package com.an22.api.promotion;

import com.an22.api.model.Promotion;
import com.an22.api.model.Promotion.Discount;
import com.an22.api.model.Promotion.FreeGoods;
import com.an22.api.model.Promotion.Scope;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** dt_promotions ของบริษัทใน transaction — scope / สาขาเป็น bigint[], ส่วนลด / ของแถมเป็น jsonb รูปเดียวกับ TS. */
@Repository
public class PromotionDao {

  private static final String SELECT =
      "SELECT id, code, name, note, type, start_date, end_date, enabled, priority, stackable, scope_all, scope_product_ids,"
          + " scope_category_ids, min_qty, min_amount, discount, free_goods, branch_ids FROM dt_promotions";

  /** โปรที่ยังไม่หมดอายุ ({@code promotionStatus() !== 'expired'}: ปิดใช้งานยังนับ) — โปรที่หมดอายุเป็นประวัติ. */
  private static final String NOT_EXPIRED = " AND NOT (enabled AND end_date IS NOT NULL AND end_date < :today)";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  PromotionDao(JdbcClient appJdbc, JsonMapper json) {
    this.jdbc = appJdbc;
    this.json = json;
  }

  public List<Promotion> list() {
    return jdbc.sql(SELECT + " ORDER BY id").query(this::row).list();
  }

  public Optional<Promotion> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::row).optional();
  }

  public long insert(Promotion p) {
    return jdbc.sql(
            "INSERT INTO dt_promotions (tenant_id, code, name, note, type, start_date, end_date, enabled, priority, stackable, scope_all,"
                + " scope_product_ids, scope_category_ids, min_qty, min_amount, discount, free_goods, branch_ids)"
                + " VALUES (app_tenant_id(), :code, :name, :note, :type, :start, :end, :enabled, :priority, :stackable, :all,"
                + " :productIds, :categoryIds, :minQty, :minAmount, CAST(:discount AS jsonb), CAST(:freeGoods AS jsonb), :branchIds)"
                + " RETURNING id")
        .params(params(p))
        .query(Long.class)
        .single();
  }

  public void update(long id, Promotion p) {
    jdbc.sql(
            "UPDATE dt_promotions SET code = :code, name = :name, note = :note, type = :type, start_date = :start, end_date = :end,"
                + " enabled = :enabled, priority = :priority, stackable = :stackable, scope_all = :all, scope_product_ids = :productIds,"
                + " scope_category_ids = :categoryIds, min_qty = :minQty, min_amount = :minAmount, discount = CAST(:discount AS jsonb),"
                + " free_goods = CAST(:freeGoods AS jsonb), branch_ids = :branchIds WHERE id = :id")
        .params(params(p))
        .param("id", id)
        .update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_promotions WHERE id = :id").param("id", id).update();
  }

  /** รหัสโปรที่ยังไม่หมดอายุซึ่งอ้าง SKU (สินค้าร่วมรายการหรือของแถม) — {@code promotionRefersToProduct()} ของ mock. */
  public List<String> codesUsingProduct(long productId, LocalDate today) {
    return jdbc.sql("SELECT code FROM dt_promotions WHERE (:id = ANY (scope_product_ids)"
            + " OR coalesce(free_goods -> 'items', '[]') @> jsonb_build_array(jsonb_build_object('productId', :id)))" + NOT_EXPIRED
            + " ORDER BY id")
        .param("id", productId)
        .param("today", today)
        .query(String.class)
        .list();
  }

  /** รหัสโปรที่ยังไม่หมดอายุซึ่งเลือกหมวดนี้ใน scope. */
  public List<String> codesUsingCategory(long categoryId, LocalDate today) {
    return jdbc.sql("SELECT code FROM dt_promotions WHERE :id = ANY (scope_category_ids)" + NOT_EXPIRED + " ORDER BY id")
        .param("id", categoryId)
        .param("today", today)
        .query(String.class)
        .list();
  }

  private Map<String, Object> params(Promotion p) {
    Map<String, Object> m = new HashMap<>();
    m.put("code", p.code());
    m.put("name", p.name());
    m.put("note", p.note());
    m.put("type", p.type());
    m.put("start", LocalDate.parse(p.startDate()));
    m.put("end", p.endDate() == null ? null : LocalDate.parse(p.endDate()));
    m.put("enabled", p.enabled());
    m.put("priority", p.priority().intValue());
    m.put("stackable", p.stackable());
    m.put("all", p.scope().all());
    m.put("productIds", p.scope().productIds().toArray(Long[]::new));
    m.put("categoryIds", p.scope().categoryIds().toArray(Long[]::new));
    m.put("minQty", p.minQty().intValue());
    m.put("minAmount", p.minAmount());
    m.put("discount", p.discount() == null ? null : json.writeValueAsString(p.discount()));
    m.put("freeGoods", p.freeGoods() == null ? null : json.writeValueAsString(p.freeGoods()));
    m.put("branchIds", p.branchIds().toArray(Long[]::new));
    return m;
  }

  private Promotion row(ResultSet rs, int i) throws SQLException {
    Date end = rs.getDate("end_date");
    String discount = rs.getString("discount");
    String free = rs.getString("free_goods");
    return new Promotion(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("note"), rs.getString("type"),
        rs.getDate("start_date").toLocalDate().toString(), end == null ? null : end.toLocalDate().toString(), rs.getBoolean("enabled"),
        (double) rs.getInt("priority"), rs.getBoolean("stackable"),
        new Scope(rs.getBoolean("scope_all"), ids(rs, "scope_product_ids"), ids(rs, "scope_category_ids")), (double) rs.getInt("min_qty"),
        rs.getBigDecimal("min_amount").doubleValue(), discount == null ? null : json.readValue(discount, Discount.class),
        free == null ? null : json.readValue(free, FreeGoods.class), ids(rs, "branch_ids"));
  }

  private static List<Long> ids(ResultSet rs, String column) throws SQLException {
    return Arrays.asList((Long[]) rs.getArray(column).getArray());
  }
}
