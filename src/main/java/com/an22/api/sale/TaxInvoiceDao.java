package com.an22.api.sale;

import com.an22.api.model.TaxInvoice;
import com.an22.api.model.TaxInvoiceBuyer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_tax_invoices ของบริษัทใน transaction — ผู้ซื้อเข้า / ถอดรหัสที่ service (dao เห็นแค่ bytes). */
@Repository
public class TaxInvoiceDao {

  /** แถวที่ผู้ซื้อยังเข้ารหัสอยู่ — service ถอดเป็น {@link TaxInvoice}. */
  public record Row(
      long id, String invoiceNo, long saleId, String orderNo, Instant saleDate, Instant date, Instant issuedAt, byte[] buyer,
      String issuedBy, Instant cancelledAt, String cancelReason, String replacesInvoiceNo, String replacedByNo, boolean atSale,
      long branchId) {

    public TaxInvoice decode(Function<byte[], TaxInvoiceBuyer> decrypt) {
      return new TaxInvoice(id, invoiceNo, saleId, orderNo, saleDate, date, issuedAt, decrypt.apply(buyer), issuedBy, cancelledAt,
          cancelReason, replacesInvoiceNo, replacedByNo, atSale, branchId);
    }
  }

  private static final String SELECT =
      "SELECT id, invoice_no, sale_id, order_no, sale_date, date, issued_at, buyer_enc, issued_by, cancelled_at, cancel_reason,"
          + " replaces_invoice_no, replaced_by_no, at_sale, branch_id FROM dt_tax_invoices";

  private final JdbcClient jdbc;

  TaxInvoiceDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /**
   * ใบใหม่ — issued_at: {@code issuedAt} (ออกพร้อมบิล = เวลาบิล) หรือ null = ตอนนี้.
   */
  public long insert(String invoiceNo, long saleId, String orderNo, Instant saleDate, Instant issuedAt, byte[] buyer, byte[] taxIdHash,
      String issuedBy, String replacesInvoiceNo, boolean atSale, long branchId) {
    return jdbc.sql("INSERT INTO dt_tax_invoices (tenant_id, invoice_no, sale_id, order_no, sale_date, date, issued_at, buyer_enc,"
            + " buyer_tax_id_hash, issued_by, replaces_invoice_no, at_sale, branch_id) VALUES (app_tenant_id(), :no, :sale, :orderNo,"
            + " :saleDate, :saleDate, coalesce(:issuedAt, now()), :buyer, :hash, :by, :replaces, :atSale, :branch) RETURNING id")
        .param("no", invoiceNo).param("sale", saleId).param("orderNo", orderNo).param("saleDate", Timestamp.from(saleDate))
        .param("issuedAt", issuedAt == null ? null : Timestamp.from(issuedAt)).param("buyer", buyer).param("hash", taxIdHash)
        .param("by", issuedBy).param("replaces", replacesInvoiceNo).param("atSale", atSale).param("branch", branchId)
        .query(Long.class).single();
  }

  /** ทุกใบของบิล เก่าก่อน. */
  public List<Row> ofSale(long saleId) {
    return jdbc.sql(SELECT + " WHERE sale_id = :sale ORDER BY id").param("sale", saleId).query(TaxInvoiceDao::row).list();
  }

  public Optional<Row> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(TaxInvoiceDao::row).optional();
  }

  /** ยกเลิกใบที่ยังใช้ได้ของบิล — คืนจำนวนที่ยกเลิก. */
  public int cancelValid(long saleId, String reason, String replacedByNo) {
    return jdbc.sql("UPDATE dt_tax_invoices SET cancelled_at = now(), cancel_reason = :reason, replaced_by_no = :by"
            + " WHERE sale_id = :sale AND cancelled_at IS NULL")
        .param("reason", reason).param("by", replacedByNo).param("sale", saleId).update();
  }

  /** ใบตามวันที่ใบกำกับ (วันท้องถิ่น) ของสาขา (null = ทุกสาขา) เก่าก่อน — ไม่เกิน {@code limit}. */
  public List<Row> list(LocalDate from, LocalDate to, Long branchId, int limit) {
    return jdbc.sql(SELECT + " WHERE (date AT TIME ZONE 'Asia/Bangkok')::date BETWEEN :from AND :to"
            + " AND (CAST(:branch AS bigint) IS NULL OR branch_id = :branch) ORDER BY date, id LIMIT :limit")
        .param("from", from).param("to", to).param("branch", branchId).param("limit", limit)
        .query(TaxInvoiceDao::row).list();
  }

  /** ใบล่าสุดที่ใช้เลขผู้ซื้อนี้ (ค้นด้วย hash — ตรงทั้งเลขเท่านั้น). */
  public Optional<Row> lastByTaxIdHash(byte[] hash) {
    return jdbc.sql(SELECT + " WHERE buyer_tax_id_hash = :hash ORDER BY id DESC LIMIT 1").param("hash", hash)
        .query(TaxInvoiceDao::row).optional();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(rs.getLong("id"), rs.getString("invoice_no"), rs.getLong("sale_id"), rs.getString("order_no"),
        instant(rs.getTimestamp("sale_date")), instant(rs.getTimestamp("date")), instant(rs.getTimestamp("issued_at")),
        rs.getBytes("buyer_enc"), rs.getString("issued_by"), instant(rs.getTimestamp("cancelled_at")), rs.getString("cancel_reason"),
        rs.getString("replaces_invoice_no"), rs.getString("replaced_by_no"), rs.getBoolean("at_sale"), rs.getLong("branch_id"));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
