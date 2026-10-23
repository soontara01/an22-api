package com.an22.api.transfer;

import com.an22.api.model.Transfer;
import com.an22.api.model.TransferLine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_transfers + dt_transfer_lines ของบริษัทใน transaction — เอกสาร: ไม่ลบ. */
@Repository
public class TransferDao {

  private static final String SELECT =
      "SELECT id, transfer_no, from_branch_id, to_branch_id, status, created_at, created_by_name, note, received_at, received_by_name,"
          + " short_reason, cancelled_at, cancelled_by_name, cancel_reason FROM dt_transfers";

  private final JdbcClient jdbc;

  TransferDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /**
   * ใบของสาขา (null = ทุกสาขา) ตามทิศ: {@code out} = ต้นทาง, {@code in} = ปลายทาง, อื่น = อย่างใดอย่างหนึ่ง — ใหม่สุดก่อน ไม่เกิน {@code limit} ใบ.
   */
  public List<Transfer> list(Long branchId, String direction, int limit) {
    String where = switch (direction == null ? "" : direction) {
      case "out" -> "from_branch_id = :branch";
      case "in" -> "to_branch_id = :branch";
      default -> "(from_branch_id = :branch OR to_branch_id = :branch)";
    };
    return withLines(jdbc.sql(SELECT + " WHERE (CAST(:branch AS bigint) IS NULL OR " + where + ") ORDER BY created_at DESC, id DESC"
            + " LIMIT :limit")
        .param("branch", branchId)
        .param("limit", limit)
        .query(TransferDao::row)
        .list());
  }

  public Optional<Transfer> find(long id) {
    return withLines(jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(TransferDao::row).list()).stream().findFirst();
  }

  /** อ่านพร้อมล็อกแถวจน commit (รับ / ยกเลิกพร้อมกันได้ครั้งเดียว). */
  public Optional<Transfer> lock(long id) {
    return withLines(jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query(TransferDao::row).list())
        .stream().findFirst();
  }

  public long insert(String transferNo, long fromBranchId, long toBranchId, String note, String createdBy, List<TransferLine> lines) {
    long id = jdbc.sql(
            "INSERT INTO dt_transfers (tenant_id, transfer_no, from_branch_id, to_branch_id, status, note, created_by_name)"
                + " VALUES (app_tenant_id(), :no, :from, :to, 'in_transit', :note, :by) RETURNING id")
        .param("no", transferNo).param("from", fromBranchId).param("to", toBranchId).param("note", note).param("by", createdBy)
        .query(Long.class)
        .single();
    for (int i = 0; i < lines.size(); i++) {
      TransferLine l = lines.get(i);
      jdbc.sql("INSERT INTO dt_transfer_lines (tenant_id, transfer_id, position, product_id, sku, name, unit, qty, serials, unit_cost)"
              + " VALUES (app_tenant_id(), :id, :pos, :product, :sku, :name, :unit, :qty, :serials, :cost)")
          .param("id", id).param("pos", i).param("product", l.productId()).param("sku", l.sku()).param("name", l.name())
          .param("unit", l.unit()).param("qty", l.qty()).param("serials", l.serials().toArray(String[]::new))
          .param("cost", BigDecimal.valueOf(l.unitCost()))
          .update();
    }
    return id;
  }

  public void received(long id, String by, String shortReason, List<TransferLine> lines) {
    jdbc.sql("UPDATE dt_transfers SET status = 'received', received_at = now(), received_by_name = :by, short_reason = :reason"
            + " WHERE id = :id")
        .param("by", by).param("reason", shortReason).param("id", id)
        .update();
    for (TransferLine l : lines) {
      jdbc.sql("UPDATE dt_transfer_lines SET received_qty = :qty, received_serials = :serials WHERE transfer_id = :id"
              + " AND product_id = :product")
          .param("qty", l.receivedQty()).param("serials", l.receivedSerials().toArray(String[]::new)).param("id", id)
          .param("product", l.productId())
          .update();
    }
  }

  public void cancelled(long id, String by, String reason) {
    jdbc.sql("UPDATE dt_transfers SET status = 'cancelled', cancelled_at = now(), cancelled_by_name = :by, cancel_reason = :reason"
            + " WHERE id = :id")
        .param("by", by).param("reason", reason).param("id", id)
        .update();
  }

  /** เลขใบที่ยังไม่รับซึ่งมี SKU นี้ (E18) เรียงตาม id. */
  public List<String> inTransitNos(long productId) {
    return jdbc.sql("SELECT t.transfer_no FROM dt_transfers t WHERE t.status = 'in_transit'"
            + " AND EXISTS (SELECT 1 FROM dt_transfer_lines l WHERE l.transfer_id = t.id AND l.product_id = :product) ORDER BY t.id")
        .param("product", productId)
        .query(String.class)
        .list();
  }

  private List<Transfer> withLines(List<Transfer> transfers) {
    if (transfers.isEmpty()) {
      return transfers;
    }
    Map<Long, List<TransferLine>> lines = new HashMap<>();
    jdbc.sql("SELECT transfer_id, product_id, sku, name, unit, qty, serials, unit_cost, received_qty, received_serials"
            + " FROM dt_transfer_lines WHERE transfer_id = ANY (:ids) ORDER BY transfer_id, position")
        .param("ids", transfers.stream().map(Transfer::id).toArray(Long[]::new))
        .query((ResultSet rs) -> {
          int got = rs.getInt("received_qty");
          Integer receivedQty = rs.wasNull() ? null : got;
          lines.computeIfAbsent(rs.getLong("transfer_id"), k -> new ArrayList<>()).add(new TransferLine(
              rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getString("unit"), rs.getInt("qty"),
              List.of((String[]) rs.getArray("serials").getArray()), rs.getBigDecimal("unit_cost").doubleValue(), receivedQty,
              List.of((String[]) rs.getArray("received_serials").getArray())));
        });
    return transfers.stream().map(t -> new Transfer(t.id(), t.transferNo(), t.fromBranchId(), t.toBranchId(), t.status(), t.createdAt(),
        t.createdBy(), t.note(), List.copyOf(lines.getOrDefault(t.id(), List.of())), t.receivedAt(), t.receivedBy(), t.shortReason(),
        t.cancelledAt(), t.cancelledBy(), t.cancelReason())).toList();
  }

  private static Transfer row(ResultSet rs, int i) throws SQLException {
    return new Transfer(rs.getLong("id"), rs.getString("transfer_no"), rs.getLong("from_branch_id"), rs.getLong("to_branch_id"),
        rs.getString("status"), instant(rs.getTimestamp("created_at")), rs.getString("created_by_name"), rs.getString("note"), List.of(),
        instant(rs.getTimestamp("received_at")), rs.getString("received_by_name"), rs.getString("short_reason"),
        instant(rs.getTimestamp("cancelled_at")), rs.getString("cancelled_by_name"), rs.getString("cancel_reason"));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
