package com.an22.api.stock;

import com.an22.api.model.SerialNumber;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_serial_numbers ของบริษัทใน transaction — serial ไม่ซ้ำทั้งบริษัท, ไม่ลบ. */
@Repository
public class SerialDao {

  private static final String SELECT =
      "SELECT id, product_id, branch_id, serial, status, received_at, removed_at, note, cost FROM dt_serial_numbers";

  private final JdbcClient jdbc;

  SerialDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** serial ของ SKU ที่สาขานี้ (คงคลัง + ที่ตัดออกที่นี่ — E16). */
  public List<SerialNumber> listAt(long productId, long branchId) {
    return jdbc.sql(SELECT + " WHERE product_id = :product AND branch_id = :branch ORDER BY id")
        .param("product", productId).param("branch", branchId)
        .query(SerialDao::row).list();
  }

  public Optional<SerialNumber> find(String serial) {
    return jdbc.sql(SELECT + " WHERE serial = :serial").param("serial", serial).query(SerialDao::row).optional();
  }

  public List<SerialNumber> findAll(List<Long> ids) {
    return jdbc.sql(SELECT + " WHERE id = ANY (:ids) ORDER BY id").param("ids", ids.toArray(Long[]::new)).query(SerialDao::row).list();
  }

  /** รับเข้า: แถวใหม่ หรือใช้แถวเดิมของ serial ที่เคยตัดออก (B18) — empty = มีคนรับเข้าตัดหน้า (ยังอยู่ในคลัง / ระหว่างโอน). */
  public Optional<SerialNumber> receive(String serial, long productId, long branchId, double cost, String returnNote) {
    return jdbc.sql(
            "INSERT INTO dt_serial_numbers (tenant_id, product_id, branch_id, serial, status, received_at, note, cost)"
                + " VALUES (app_tenant_id(), :product, :branch, :serial, 'in_stock', now(), '', :cost)"
                + " ON CONFLICT (tenant_id, serial) DO UPDATE SET product_id = EXCLUDED.product_id, branch_id = EXCLUDED.branch_id,"
                + " status = 'in_stock', received_at = now(), removed_at = NULL, note = :note, cost = EXCLUDED.cost"
                + " WHERE dt_serial_numbers.status NOT IN ('in_stock', 'in_transit')"
                + " RETURNING id, product_id, branch_id, serial, status, received_at, removed_at, note, cost")
        .param("product", productId).param("branch", branchId).param("serial", serial).param("note", returnNote)
        .param("cost", BigDecimal.valueOf(cost))
        .query(SerialDao::row)
        .optional();
  }

  /** ตัดออก serial ที่ยังอยู่ในคลังของสาขานี้ — คืนจำนวนแถวที่เปลี่ยน (น้อยกว่าที่ขอ = ถูกขาย / ตัดไปก่อน). */
  public int remove(List<Long> ids, long productId, long branchId, String status, String note) {
    return jdbc.sql(
            "UPDATE dt_serial_numbers SET status = :status, removed_at = now(), note = :note"
                + " WHERE id = ANY (:ids) AND product_id = :product AND branch_id = :branch AND status = 'in_stock'")
        .param("status", status).param("note", note).param("ids", ids.toArray(Long[]::new))
        .param("product", productId).param("branch", branchId)
        .update();
  }

  /** ทุนของ serial ตามรายการ (ลำดับตาม id) — ใช้คิดทุนโอน / ทุนของที่ขาด. */
  public List<Double> costsOf(List<String> serials) {
    return jdbc.sql("SELECT cost FROM dt_serial_numbers WHERE serial = ANY (:serials) ORDER BY id")
        .param("serials", serials.toArray(String[]::new))
        .query((rs, i) -> rs.getBigDecimal("cost").doubleValue())
        .list();
  }

  /**
   * ออกจากคลังของสาขา (in_stock → in_transit / damaged …) — คืนจำนวนแถวที่เปลี่ยน (น้อยกว่าที่ขอ = ถูกขาย / ย้ายไปก่อน).
   */
  public int takeOut(List<String> serials, long productId, long branchId, String status, String note) {
    return jdbc.sql(
            "UPDATE dt_serial_numbers SET status = :status, removed_at = now(), note = :note"
                + " WHERE serial = ANY (:serials) AND product_id = :product AND branch_id = :branch AND status = 'in_stock'")
        .param("status", status).param("note", note).param("serials", serials.toArray(String[]::new))
        .param("product", productId).param("branch", branchId)
        .update();
  }

  /** ระหว่างโอน → เข้าคลังของสาขานี้ (ปลายทางรับ / ต้นทางเมื่อยกเลิก) — คืนจำนวนแถวที่เปลี่ยน. */
  public int arrive(List<String> serials, long productId, long branchId, String note) {
    return jdbc.sql(
            "UPDATE dt_serial_numbers SET branch_id = :branch, status = 'in_stock', removed_at = NULL, received_at = now(), note = :note"
                + " WHERE serial = ANY (:serials) AND product_id = :product AND status = 'in_transit'")
        .param("note", note).param("serials", serials.toArray(String[]::new))
        .param("product", productId).param("branch", branchId)
        .update();
  }

  /** serial ที่ขายแล้วกลับเข้าคลังของสาขานี้ (ยกเลิกบิล) — คืนจำนวนแถวที่เปลี่ยน. */
  public int returnSold(String serial, long productId, long branchId, String note) {
    return jdbc.sql(
            "UPDATE dt_serial_numbers SET branch_id = :branch, status = 'in_stock', removed_at = NULL, received_at = now(), note = :note"
                + " WHERE serial = :serial AND product_id = :product AND status = 'sold'")
        .param("branch", branchId).param("note", note).param("serial", serial).param("product", productId)
        .update();
  }

  /** ทุนของ serial ที่อยู่ในคลังของสาขานี้ (ใช้คิดสต็อก / ทุนเฉลี่ยของ SKU serial). */
  public List<Double> inStockCosts(long productId, long branchId) {
    return jdbc.sql("SELECT cost FROM dt_serial_numbers WHERE product_id = :product AND branch_id = :branch AND status = 'in_stock'"
            + " ORDER BY id")
        .param("product", productId).param("branch", branchId)
        .query((rs, i) -> rs.getBigDecimal("cost").doubleValue())
        .list();
  }

  private static SerialNumber row(ResultSet rs, int i) throws SQLException {
    Timestamp removed = rs.getTimestamp("removed_at");
    return new SerialNumber(
        rs.getLong("id"), rs.getLong("product_id"), rs.getLong("branch_id"), rs.getString("serial"), rs.getString("status"),
        rs.getTimestamp("received_at").toInstant(), removed == null ? null : removed.toInstant(), rs.getString("note"),
        rs.getBigDecimal("cost").doubleValue());
  }
}
