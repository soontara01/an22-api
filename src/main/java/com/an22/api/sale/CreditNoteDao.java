package com.an22.api.sale;

import com.an22.api.model.CreditNote;
import com.an22.api.model.SalePayment;
import com.an22.api.rules.ShiftRules;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_credit_notes + บรรทัด / หัก / คืนเงิน ของบริษัทใน transaction — ผู้ซื้อยังเข้ารหัส (service ถอด). เอกสาร append-only. */
@Repository
public class CreditNoteDao {

  /** ใบลดหนี้ (buyer = null) + ผู้ซื้อที่ยังเข้ารหัส. */
  public record Row(CreditNote note, byte[] buyer) {}

  private static final String SELECT =
      "SELECT id, cn_no, sale_id, order_no, tax_invoice_no, buyer_enc, sale_date, date, cashier, reason, subtotal, deduction, total, vat,"
          + " shift_id, branch_id, terminal_id, pos_id, vat_registered FROM dt_credit_notes";

  private final JdbcClient jdbc;

  CreditNoteDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public long insert(CreditNote n, byte[] buyer) {
    Map<String, Object> m = new HashMap<>();
    m.put("no", n.cnNo());
    m.put("sale", n.saleId());
    m.put("orderNo", n.orderNo());
    m.put("invoiceNo", n.taxInvoiceNo());
    m.put("buyer", buyer);
    m.put("saleDate", Timestamp.from(n.saleDate()));
    m.put("cashier", n.cashier());
    m.put("reason", n.reason());
    m.put("subtotal", BigDecimal.valueOf(n.subtotal()));
    m.put("deduction", BigDecimal.valueOf(n.deduction()));
    m.put("total", BigDecimal.valueOf(n.total()));
    m.put("vat", BigDecimal.valueOf(n.vat()));
    m.put("shift", n.shiftId());
    m.put("branch", n.branchId());
    m.put("terminal", n.terminalId());
    m.put("posId", n.posId());
    m.put("vatRegistered", n.vatRegistered());
    long id = jdbc.sql("INSERT INTO dt_credit_notes (tenant_id, cn_no, sale_id, order_no, tax_invoice_no, buyer_enc, sale_date, cashier,"
            + " reason, subtotal, deduction, total, vat, shift_id, branch_id, terminal_id, pos_id, vat_registered) VALUES (app_tenant_id(),"
            + " :no, :sale, :orderNo, :invoiceNo, :buyer, :saleDate, :cashier, :reason, :subtotal, :deduction, :total, :vat, :shift,"
            + " :branch, :terminal, :posId, :vatRegistered) RETURNING id")
        .params(m).query(Long.class).single();
    for (int i = 0; i < n.lines().size(); i++) {
      CreditNote.Line l = n.lines().get(i);
      Map<String, Object> p = new HashMap<>();
      p.put("note", id);
      p.put("pos", i);
      p.put("index", l.saleLineIndex());
      p.put("product", l.productId());
      p.put("sku", l.sku());
      p.put("name", l.name());
      p.put("shortName", l.shortName());
      p.put("unit", l.unit());
      p.put("factor", l.factor());
      p.put("qty", l.qty());
      p.put("amount", BigDecimal.valueOf(l.amount()));
      p.put("vatType", l.vatType());
      p.put("vat", BigDecimal.valueOf(l.vat()));
      p.put("cogs", BigDecimal.valueOf(l.cogs()));
      p.put("serial", l.serial());
      p.put("free", l.free());
      p.put("restock", l.restock());
      jdbc.sql("INSERT INTO dt_credit_note_lines (tenant_id, credit_note_id, position, sale_line_index, product_id, sku, name, short_name,"
              + " unit, factor, qty, amount, vat_type, vat, cogs, serial, free, restock) VALUES (app_tenant_id(), :note, :pos, :index,"
              + " :product, :sku, :name, :shortName, :unit, :factor, :qty, :amount, :vatType, :vat, :cogs, :serial, :free, :restock)")
          .params(p).update();
    }
    for (int i = 0; i < n.deductions().size(); i++) {
      CreditNote.Deduction d = n.deductions().get(i);
      jdbc.sql("INSERT INTO dt_credit_note_deductions (tenant_id, credit_note_id, position, promotion_id, product_id, sku, name, qty,"
              + " unit_price, amount, vat_type) VALUES (app_tenant_id(), :note, :pos, :promotion, :product, :sku, :name, :qty, :unitPrice,"
              + " :amount, :vatType)")
          .param("note", id).param("pos", i).param("promotion", d.promotionId()).param("product", d.productId()).param("sku", d.sku())
          .param("name", d.name()).param("qty", d.qty()).param("unitPrice", BigDecimal.valueOf(d.unitPrice()))
          .param("amount", BigDecimal.valueOf(d.amount())).param("vatType", d.vatType())
          .update();
    }
    for (int i = 0; i < n.refunds().size(); i++) {
      SalePayment r = n.refunds().get(i);
      jdbc.sql("INSERT INTO dt_credit_note_refunds (tenant_id, credit_note_id, position, method_id, name, type, amount, tendered, reference,"
              + " installment_months) VALUES (app_tenant_id(), :note, :pos, :method, :name, :type, :amount, :tendered, :reference, :months)")
          .param("note", id).param("pos", i).param("method", r.methodId()).param("name", r.name()).param("type", r.type())
          .param("amount", BigDecimal.valueOf(r.amount())).param("tendered", BigDecimal.valueOf(r.tendered()))
          .param("reference", r.reference()).param("months", r.installmentMonths())
          .update();
    }
    return id;
  }

  public Optional<Row> find(long id) {
    return withChildren(jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(CreditNoteDao::row).list()).stream().findFirst();
  }

  /** ใบของบิล เก่าก่อน. */
  public List<Row> ofSale(long saleId) {
    return withChildren(jdbc.sql(SELECT + " WHERE sale_id = :sale ORDER BY date, id").param("sale", saleId).query(CreditNoteDao::row).list());
  }

  /** ใบตามวันที่ (วันท้องถิ่น) ของสาขา (null = ทุกสาขา) ใหม่สุดก่อน — ไม่เกิน {@code limit}. */
  public List<Row> list(LocalDate from, LocalDate to, Long branchId, int limit) {
    return withChildren(jdbc.sql(SELECT + " WHERE (date AT TIME ZONE 'Asia/Bangkok')::date BETWEEN :from AND :to"
            + " AND (CAST(:branch AS bigint) IS NULL OR branch_id = :branch) ORDER BY date DESC, id DESC LIMIT :limit")
        .param("from", from).param("to", to).param("branch", branchId).param("limit", limit)
        .query(CreditNoteDao::row).list());
  }

  /** ใบที่คืนเงินจากกะ ใหม่สุดก่อน — ไม่เกิน {@code limit}. */
  public List<Row> ofShift(long shiftId, int limit) {
    return withChildren(jdbc.sql(SELECT + " WHERE shift_id = :shift ORDER BY date DESC, id DESC LIMIT :limit")
        .param("shift", shiftId).param("limit", limit).query(CreditNoteDao::row).list());
  }

  /** การคืนเงินของกะ (ยอดระบบ — {@code expectedByMethod()}). */
  public List<ShiftRules.CreditNoteRef> shiftRefs(long shiftId) {
    return ofShift(shiftId, Integer.MAX_VALUE).stream()
        .map(r -> new ShiftRules.CreditNoteRef(r.note().shiftId(), r.note().refunds(), r.note().total())).toList();
  }

  public boolean terminalUsed(int terminalId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_credit_notes WHERE terminal_id = :id)").param("id", terminalId)
        .query(Boolean.class).single();
  }

  private List<Row> withChildren(List<Row> rows) {
    if (rows.isEmpty()) {
      return rows;
    }
    Long[] ids = rows.stream().map(r -> r.note().id()).toArray(Long[]::new);
    Map<Long, List<CreditNote.Line>> lines = new HashMap<>();
    jdbc.sql("SELECT credit_note_id, sale_line_index, product_id, sku, name, short_name, unit, factor, qty, amount, vat_type, vat, cogs,"
            + " serial, free, restock FROM dt_credit_note_lines WHERE credit_note_id = ANY (:ids) ORDER BY credit_note_id, position")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          lines.computeIfAbsent(rs.getLong("credit_note_id"), k -> new ArrayList<>()).add(new CreditNote.Line(
              rs.getInt("sale_line_index"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"),
              rs.getString("short_name"), rs.getString("unit"), rs.getInt("factor"), rs.getInt("qty"), dbl(rs, "amount"),
              rs.getString("vat_type"), dbl(rs, "vat"), dbl(rs, "cogs"), rs.getString("serial"), rs.getBoolean("free"),
              rs.getBoolean("restock")));
        });
    Map<Long, List<CreditNote.Deduction>> deductions = new HashMap<>();
    jdbc.sql("SELECT credit_note_id, promotion_id, product_id, sku, name, qty, unit_price, amount, vat_type FROM dt_credit_note_deductions"
            + " WHERE credit_note_id = ANY (:ids) ORDER BY credit_note_id, position")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          deductions.computeIfAbsent(rs.getLong("credit_note_id"), k -> new ArrayList<>()).add(new CreditNote.Deduction(
              rs.getLong("promotion_id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getInt("qty"),
              dbl(rs, "unit_price"), dbl(rs, "amount"), rs.getString("vat_type")));
        });
    Map<Long, List<SalePayment>> refunds = new HashMap<>();
    jdbc.sql("SELECT credit_note_id, method_id, name, type, amount, tendered, reference, installment_months FROM dt_credit_note_refunds"
            + " WHERE credit_note_id = ANY (:ids) ORDER BY credit_note_id, position")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          int months = rs.getInt("installment_months");
          Integer installmentMonths = rs.wasNull() ? null : months;
          refunds.computeIfAbsent(rs.getLong("credit_note_id"), k -> new ArrayList<>()).add(new SalePayment(
              rs.getLong("method_id"), rs.getString("name"), rs.getString("type"), dbl(rs, "amount"), dbl(rs, "tendered"),
              rs.getString("reference"), installmentMonths));
        });
    return rows.stream().map(r -> {
      CreditNote n = r.note();
      return new Row(new CreditNote(n.id(), n.cnNo(), n.saleId(), n.orderNo(), n.taxInvoiceNo(), null, n.saleDate(), n.date(), n.cashier(),
          n.reason(), List.copyOf(lines.getOrDefault(n.id(), List.of())), List.copyOf(deductions.getOrDefault(n.id(), List.of())),
          n.subtotal(), n.deduction(), n.total(), n.vat(), List.copyOf(refunds.getOrDefault(n.id(), List.of())), n.shiftId(), n.branchId(),
          n.terminalId(), n.posId(), n.vatRegistered()), r.buyer());
    }).toList();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    long shift = rs.getLong("shift_id");
    Long shiftId = rs.wasNull() ? null : shift;
    int terminal = rs.getInt("terminal_id");
    Integer terminalId = rs.wasNull() ? null : terminal;
    CreditNote n = new CreditNote(rs.getLong("id"), rs.getString("cn_no"), rs.getLong("sale_id"), rs.getString("order_no"),
        rs.getString("tax_invoice_no"), null, instant(rs.getTimestamp("sale_date")), instant(rs.getTimestamp("date")),
        rs.getString("cashier"), rs.getString("reason"), List.of(), List.of(), dbl(rs, "subtotal"), dbl(rs, "deduction"), dbl(rs, "total"),
        dbl(rs, "vat"), List.of(), shiftId, rs.getLong("branch_id"), terminalId, rs.getString("pos_id"), rs.getBoolean("vat_registered"));
    return new Row(n, rs.getBytes("buyer_enc"));
  }

  private static double dbl(ResultSet rs, String column) throws SQLException {
    return rs.getBigDecimal(column).doubleValue();
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
