package com.an22.api.product;

import com.an22.api.model.PackUnit;
import com.an22.api.model.Product;
import com.an22.api.model.SkuSupplier;
import com.an22.api.rules.ProductRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * dt_products + หน่วยแพ็ก + ผู้จำหน่าย ของบริษัทใน transaction — อ่าน "ที่สาขา" = เติม stock / avgCost / min / max ของ dt_branch_stock สาขานั้น
 * (ไม่มีแถว = 0, D1 / E13); categoryPath / currentPrice ให้ service เติม.
 */
@Repository
public class ProductDao {

  private static final String SELECT_AT =
      "SELECT p.id, p.sku, p.name, p.short_name, p.category_id, p.unit, p.barcode, p.brand, p.model, p.item_type, p.image_url,"
          + " p.vat_type, p.cost, p.warranty_months, p.serial_control, p.serial_prefix, p.serial_length, p.sale_status,"
          + " coalesce(s.stock, 0) AS stock, coalesce(s.avg_cost, 0) AS avg_cost, coalesce(s.min_stock, 0) AS min_stock,"
          + " coalesce(s.max_stock, 0) AS max_stock"
          + " FROM dt_products p LEFT JOIN dt_branch_stock s ON s.product_id = p.id AND s.branch_id = :branch";

  private final JdbcClient jdbc;

  ProductDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** ทุก SKU ที่สาขานี้ เรียงตาม id. */
  public List<Product> listAt(long branchId) {
    List<Row> rows = jdbc.sql(SELECT_AT + " ORDER BY p.id").param("branch", branchId).query(ProductDao::row).list();
    return withChildren(rows, null);
  }

  public Optional<Product> findAt(long id, long branchId) {
    List<Row> rows = jdbc.sql(SELECT_AT + " WHERE p.id = :id").param("branch", branchId).param("id", id).query(ProductDao::row).list();
    return withChildren(rows, id).stream().findFirst();
  }

  /** SKU อื่นของบริษัท (รหัส + บาร์โค้ดทั้งหมด) สำหรับกฎ "ไม่ซ้ำ". */
  public List<ProductRules.Other> others(Long exceptId) {
    return jdbc.sql(
            "SELECT p.sku, array_remove(array_prepend(nullif(p.barcode, ''),"
                + " ARRAY(SELECT nullif(u.barcode, '') FROM dt_product_pack_units u WHERE u.product_id = p.id)), NULL) AS codes"
                + " FROM dt_products p WHERE (CAST(:except AS bigint) IS NULL OR p.id <> :except)")
        .param("except", exceptId)
        .query((rs, i) -> new ProductRules.Other(rs.getString("sku"), List.of((String[]) rs.getArray("codes").getArray())))
        .list();
  }

  public long insert(Product p) {
    long id = jdbc.sql(
            "INSERT INTO dt_products (tenant_id, sku, name, short_name, category_id, unit, barcode, brand, model, item_type, image_url,"
                + " vat_type, cost, warranty_months, serial_control, serial_prefix, serial_length, sale_status)"
                + " VALUES (app_tenant_id(), :sku, :name, :shortName, :categoryId, :unit, :barcode, :brand, :model, :itemType, :imageUrl,"
                + " :vatType, :cost, :warrantyMonths, :serialControl, :serialPrefix, :serialLength, :saleStatus) RETURNING id")
        .params(params(p))
        .query(Long.class)
        .single();
    setChildren(id, p);
    return id;
  }

  public void update(long id, Product p) {
    jdbc.sql(
            "UPDATE dt_products SET sku = :sku, name = :name, short_name = :shortName, category_id = :categoryId, unit = :unit,"
                + " barcode = :barcode, brand = :brand, model = :model, item_type = :itemType, image_url = :imageUrl,"
                + " vat_type = :vatType, cost = :cost, warranty_months = :warrantyMonths, serial_control = :serialControl,"
                + " serial_prefix = :serialPrefix, serial_length = :serialLength, sale_status = :saleStatus WHERE id = :id")
        .params(params(p))
        .param("id", id)
        .update();
    setChildren(id, p);
  }

  /** ลบ SKU ที่หมดสต็อกแล้ว: แถวลูก + แถวสต็อกต่อสาขา (เป็น 0 ทั้งหมด) — ประวัติ (movement / serial) อยู่ต่อ. */
  public void delete(long id) {
    for (String table : List.of("dt_product_pack_units", "dt_product_suppliers", "dt_branch_stock")) {
      jdbc.sql("DELETE FROM " + table + " WHERE product_id = :id").param("id", id).update();
    }
    jdbc.sql("DELETE FROM dt_products WHERE id = :id").param("id", id).update();
  }

  /** จุดสั่งซื้อ / สต็อกสูงสุดของสาขา (E13) — แถวใหม่สร้างเมื่อมีค่าไม่ใช่ 0 เท่านั้น (เหมือน mock). */
  public void setLevels(long branchId, long productId, int min, int max) {
    jdbc.sql(
            "INSERT INTO dt_branch_stock (tenant_id, branch_id, product_id, min_stock, max_stock)"
                + " SELECT app_tenant_id(), :branch, :product, :min, :max WHERE :min <> 0 OR :max <> 0"
                + " ON CONFLICT (tenant_id, branch_id, product_id) DO UPDATE SET min_stock = EXCLUDED.min_stock,"
                + " max_stock = EXCLUDED.max_stock")
        .param("branch", branchId).param("product", productId).param("min", min).param("max", max)
        .update();
    jdbc.sql("UPDATE dt_branch_stock SET min_stock = 0, max_stock = 0 WHERE branch_id = :branch AND product_id = :product"
            + " AND :min = 0 AND :max = 0")
        .param("branch", branchId).param("product", productId).param("min", min).param("max", max)
        .update();
  }

  /** รหัสสาขาที่ยังมีของของ SKU นี้ (เรียงตาม id สาขา) — E18. */
  public List<String> stockedBranchCodes(long productId) {
    return jdbc.sql(
            "SELECT b.code FROM dt_branch_stock s JOIN dt_branches b ON b.id = s.branch_id"
                + " WHERE s.product_id = :id AND s.stock > 0 ORDER BY b.id")
        .param("id", productId)
        .query(String.class)
        .list();
  }

  private void setChildren(long id, Product p) {
    jdbc.sql("DELETE FROM dt_product_pack_units WHERE product_id = :id").param("id", id).update();
    for (int i = 0; i < p.packUnits().size(); i++) {
      PackUnit u = p.packUnits().get(i);
      jdbc.sql("INSERT INTO dt_product_pack_units (tenant_id, product_id, position, unit, factor, barcode)"
              + " VALUES (app_tenant_id(), :id, :pos, :unit, :factor, :barcode)")
          .param("id", id).param("pos", i).param("unit", u.unit().strip()).param("factor", u.factor())
          .param("barcode", u.barcode() == null ? "" : u.barcode())
          .update();
    }
    jdbc.sql("DELETE FROM dt_product_suppliers WHERE product_id = :id").param("id", id).update();
    for (int i = 0; i < p.suppliers().size(); i++) {
      SkuSupplier s = p.suppliers().get(i);
      jdbc.sql("INSERT INTO dt_product_suppliers (tenant_id, product_id, supplier_id, position, supplier_sku, cost, lead_time_days,"
              + " moq, is_main) VALUES (app_tenant_id(), :id, :supplier, :pos, :sku, :cost, :lead, :moq, :main)")
          .param("id", id).param("supplier", s.supplierId()).param("pos", i)
          .param("sku", s.supplierSku() == null ? "" : s.supplierSku()).param("cost", s.cost()).param("lead", s.leadTimeDays())
          .param("moq", s.moq()).param("main", Boolean.TRUE.equals(s.isMain()))
          .update();
    }
  }

  private static Map<String, Object> params(Product p) {
    Map<String, Object> m = new HashMap<>();
    m.put("sku", p.sku());
    m.put("name", p.name());
    m.put("shortName", p.shortName());
    m.put("categoryId", p.categoryId());
    m.put("unit", p.unit());
    m.put("barcode", p.barcode());
    m.put("brand", p.brand());
    m.put("model", p.model());
    m.put("itemType", p.itemType());
    m.put("imageUrl", p.imageUrl());
    m.put("vatType", p.vatType());
    m.put("cost", p.cost());
    m.put("warrantyMonths", p.warrantyMonths());
    m.put("serialControl", p.serialControl());
    m.put("serialPrefix", p.serialPrefix());
    m.put("serialLength", p.serialLength());
    m.put("saleStatus", p.saleStatus());
    return m;
  }

  /** แถวของ SKU ก่อนเติมแพ็ก / ผู้จำหน่าย. */
  private record Row(Product product) {}

  private List<Product> withChildren(List<Row> rows, Long only) {
    if (rows.isEmpty()) {
      return List.of();
    }
    String where = only == null ? "" : " WHERE product_id = :id";
    Map<Long, List<PackUnit>> packs = new HashMap<>();
    jdbc.sql("SELECT product_id, unit, factor, barcode FROM dt_product_pack_units" + where + " ORDER BY product_id, position")
        .params(only == null ? Map.of() : Map.of("id", only))
        .query((ResultSet rs) -> {
          packs.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
              .add(new PackUnit(rs.getString("unit"), rs.getInt("factor"), rs.getString("barcode")));
        });
    Map<Long, List<SkuSupplier>> suppliers = new HashMap<>();
    jdbc.sql("SELECT product_id, supplier_id, supplier_sku, cost, lead_time_days, moq, is_main FROM dt_product_suppliers" + where
            + " ORDER BY product_id, position")
        .params(only == null ? Map.of() : Map.of("id", only))
        .query((ResultSet rs) -> {
          suppliers.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
              .add(new SkuSupplier(rs.getLong("supplier_id"), rs.getString("supplier_sku"), rs.getBigDecimal("cost").doubleValue(),
                  rs.getInt("lead_time_days"), rs.getInt("moq"), rs.getBoolean("is_main")));
        });
    return rows.stream().map(r -> {
      Product p = r.product();
      return new Product(p.id(), p.sku(), p.name(), p.shortName(), p.categoryId(), p.categoryPath(), p.unit(), p.barcode(),
          List.copyOf(packs.getOrDefault(p.id(), List.of())), p.brand(), p.model(), p.itemType(), p.imageUrl(), p.vatType(), p.cost(),
          p.avgCost(), p.currentPrice(), p.stock(), p.minStock(), p.maxStock(), List.copyOf(suppliers.getOrDefault(p.id(), List.of())),
          p.warrantyMonths(), p.serialControl(), p.serialPrefix(), p.serialLength(), p.saleStatus());
    }).toList();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    long category = rs.getLong("category_id");
    Long categoryId = rs.wasNull() ? null : category;
    int length = rs.getInt("serial_length");
    Integer serialLength = rs.wasNull() ? null : length;
    return new Row(new Product(
        rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("short_name"), categoryId, null,
        rs.getString("unit"), rs.getString("barcode"), List.of(), rs.getString("brand"), rs.getString("model"),
        rs.getString("item_type"), rs.getString("image_url"), rs.getString("vat_type"), rs.getBigDecimal("cost").doubleValue(),
        rs.getBigDecimal("avg_cost").doubleValue(), null, rs.getInt("stock"), rs.getInt("min_stock"), rs.getInt("max_stock"),
        List.of(), rs.getInt("warranty_months"), rs.getBoolean("serial_control"), rs.getString("serial_prefix"), serialLength,
        rs.getString("sale_status")));
  }
}
