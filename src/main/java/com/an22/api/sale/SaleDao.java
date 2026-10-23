package com.an22.api.sale;

import com.an22.api.model.Sale;
import com.an22.api.model.SaleLine;
import com.an22.api.model.SalePayment;
import com.an22.api.rules.Money;
import com.an22.api.rules.ShiftRules;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * dt_sales + บรรทัด + การชำระ ของบริษัทใน transaction — เอกสาร: ไม่ลบ, บรรทัด / การชำระไม่เปลี่ยน. returnStatus / creditedTotal /
 * creditNoteNos / exchangeNos คำนวณจากใบลดหนี้ / ใบเปลี่ยนทุกครั้งที่อ่าน ({@code returnSummary()} / {@code exchangeNos()}).
 */
@Repository
public class SaleDao {

  private static final String SELECT =
      "SELECT id, order_no, sold_at, cashier, customer, subtotal, item_discount, bill_discount, manual_discount, total, vat, rounding,"
          + " change, bill_promotion_ids, item_count, status, voided_at, void_reason, voided_by, void_approved_by, tax_invoice_no,"
          + " manual_discount_reason, manual_discount_by, shift_id, void_shift_id, branch_id, terminal_id, pos_id, vat_registered"
          + " FROM dt_sales";

  private final JdbcClient jdbc;

  SaleDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  /** บิลใหม่ + บรรทัด + การชำระ (ค่าที่ server คำนวณแล้ว) — คืน id. */
  public long insert(Sale s) {
    Map<String, Object> m = new HashMap<>();
    m.put("no", s.orderNo());
    m.put("cashier", s.cashier());
    m.put("customer", s.customer());
    m.put("subtotal", BigDecimal.valueOf(s.subtotal()));
    m.put("itemDiscount", BigDecimal.valueOf(s.itemDiscount()));
    m.put("billDiscount", BigDecimal.valueOf(s.billDiscount()));
    m.put("manualDiscount", BigDecimal.valueOf(s.manualDiscount()));
    m.put("total", BigDecimal.valueOf(s.total()));
    m.put("vat", BigDecimal.valueOf(s.vat()));
    m.put("rounding", BigDecimal.valueOf(s.rounding()));
    m.put("change", BigDecimal.valueOf(s.change()));
    m.put("billPromotionIds", s.billPromotionIds().toArray(Long[]::new));
    m.put("itemCount", s.itemCount());
    m.put("reason", s.manualDiscountReason());
    m.put("by", s.manualDiscountBy());
    m.put("shift", s.shiftId());
    m.put("branch", s.branchId());
    m.put("terminal", s.terminalId());
    m.put("posId", s.posId());
    m.put("vatRegistered", s.vatRegistered());
    long id = jdbc.sql(
            "INSERT INTO dt_sales (tenant_id, order_no, cashier, customer, subtotal, item_discount, bill_discount, manual_discount, total,"
                + " vat, rounding, change, bill_promotion_ids, item_count, status, manual_discount_reason, manual_discount_by, shift_id,"
                + " branch_id, terminal_id, pos_id, vat_registered) VALUES (app_tenant_id(), :no, :cashier, :customer, :subtotal,"
                + " :itemDiscount, :billDiscount, :manualDiscount, :total, :vat, :rounding, :change, :billPromotionIds, :itemCount, 'paid',"
                + " :reason, :by, :shift, :branch, :terminal, :posId, :vatRegistered) RETURNING id")
        .params(m).query(Long.class).single();
    for (int i = 0; i < s.lines().size(); i++) {
      SaleLine l = s.lines().get(i);
      Map<String, Object> p = new HashMap<>();
      p.put("sale", id);
      p.put("index", i);
      p.put("product", l.productId());
      p.put("sku", l.sku());
      p.put("name", l.name());
      p.put("shortName", l.shortName());
      p.put("unit", l.unit());
      p.put("factor", l.factor());
      p.put("qty", l.qty());
      p.put("unitPrice", BigDecimal.valueOf(l.unitPrice()));
      p.put("listPrice", BigDecimal.valueOf(l.listPrice()));
      p.put("itemDiscount", BigDecimal.valueOf(l.itemDiscount()));
      p.put("billDiscount", BigDecimal.valueOf(l.billDiscount()));
      p.put("manualDiscount", BigDecimal.valueOf(l.manualDiscount()));
      p.put("amount", BigDecimal.valueOf(l.amount()));
      p.put("vatType", l.vatType());
      p.put("itemType", l.itemType());
      p.put("vat", BigDecimal.valueOf(l.vat()));
      p.put("promotionIds", l.promotionIds().toArray(Long[]::new));
      p.put("freeOf", l.freeOfPromotionId());
      p.put("serial", l.serial());
      p.put("warranty", l.warrantyMonths());
      p.put("cogs", BigDecimal.valueOf(l.cogs()));
      jdbc.sql("INSERT INTO dt_sale_lines (tenant_id, sale_id, line_index, product_id, sku, name, short_name, unit, factor, qty, unit_price,"
              + " list_price, item_discount, bill_discount, manual_discount, amount, vat_type, item_type, vat, promotion_ids,"
              + " free_of_promotion_id, serial, warranty_months, cogs) VALUES (app_tenant_id(), :sale, :index, :product, :sku, :name,"
              + " :shortName, :unit, :factor, :qty, :unitPrice, :listPrice, :itemDiscount, :billDiscount, :manualDiscount, :amount,"
              + " :vatType, :itemType, :vat, :promotionIds, :freeOf, :serial, :warranty, :cogs)")
          .params(p).update();
    }
    for (int i = 0; i < s.payments().size(); i++) {
      SalePayment p = s.payments().get(i);
      jdbc.sql("INSERT INTO dt_sale_payments (tenant_id, sale_id, position, method_id, name, type, amount, tendered, reference,"
              + " installment_months) VALUES (app_tenant_id(), :sale, :pos, :method, :name, :type, :amount, :tendered, :reference,"
              + " :months)")
          .param("sale", id).param("pos", i).param("method", p.methodId()).param("name", p.name()).param("type", p.type())
          .param("amount", BigDecimal.valueOf(p.amount())).param("tendered", BigDecimal.valueOf(p.tendered()))
          .param("reference", p.reference()).param("months", p.installmentMonths())
          .update();
    }
    return id;
  }

  public Optional<Sale> find(long id) {
    return withChildren(jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(SaleDao::row).list()).stream().findFirst();
  }

  /** อ่านพร้อมล็อกแถวบิลจน commit (void / ออกใบกำกับ ทีละ request). */
  public Optional<Sale> lock(long id) {
    jdbc.sql("SELECT id FROM dt_sales WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
    return find(id);
  }

  /** บิลตามวันขาย (วันท้องถิ่น) ของสาขา (null = ทุกสาขา) ใหม่สุดก่อน — ไม่เกิน {@code limit}. */
  public List<Sale> list(LocalDate from, LocalDate to, Long branchId, int limit) {
    return withChildren(jdbc.sql(SELECT + " WHERE (sold_at AT TIME ZONE 'Asia/Bangkok')::date BETWEEN :from AND :to"
            + " AND (CAST(:branch AS bigint) IS NULL OR branch_id = :branch) ORDER BY sold_at DESC, id DESC LIMIT :limit")
        .param("from", from).param("to", to).param("branch", branchId).param("limit", limit)
        .query(SaleDao::row).list());
  }

  /** บิลที่ขายหรือ void ในกะ ใหม่สุดก่อน — ไม่เกิน {@code limit}. */
  public List<Sale> ofShift(long shiftId, int limit) {
    return withChildren(jdbc.sql(SELECT + " WHERE shift_id = :shift OR void_shift_id = :shift ORDER BY sold_at DESC, id DESC LIMIT :limit")
        .param("shift", shiftId).param("limit", limit)
        .query(SaleDao::row).list());
  }

  /** เงินของบิลที่ขาย / void ในกะ (ยอดระบบของกะ — {@code expectedByMethod()}). */
  public List<ShiftRules.SaleRef> shiftRefs(long shiftId) {
    return ofShift(shiftId, Integer.MAX_VALUE).stream()
        .map(s -> new ShiftRules.SaleRef(s.shiftId(), s.voidShiftId(), s.payments(), s.total(), s.itemDiscount(), s.billDiscount(),
            s.manualDiscount()))
        .toList();
  }

  public void voided(long id, String reason, Long voidShiftId, String by, String approvedBy) {
    jdbc.sql("UPDATE dt_sales SET status = 'cancelled', voided_at = now(), void_reason = :reason, void_shift_id = :shift,"
            + " voided_by = :by, void_approved_by = :approvedBy WHERE id = :id")
        .param("reason", reason).param("shift", voidShiftId).param("by", by).param("approvedBy", approvedBy).param("id", id)
        .update();
  }

  public void setStatus(long id, String status) {
    jdbc.sql("UPDATE dt_sales SET status = :status WHERE id = :id").param("status", status).param("id", id).update();
  }

  public void setTaxInvoiceNo(long id, String invoiceNo) {
    jdbc.sql("UPDATE dt_sales SET tax_invoice_no = :no WHERE id = :id").param("no", invoiceNo).param("id", id).update();
  }

  public boolean productSold(long productId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_sale_lines WHERE product_id = :id)").param("id", productId)
        .query(Boolean.class).single();
  }

  public boolean methodUsed(long methodId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_sale_payments WHERE method_id = :id)").param("id", methodId)
        .query(Boolean.class).single();
  }

  public boolean terminalUsed(int terminalId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_sales WHERE terminal_id = :id)").param("id", terminalId)
        .query(Boolean.class).single();
  }

  private List<Sale> withChildren(List<Sale> sales) {
    if (sales.isEmpty()) {
      return sales;
    }
    Long[] ids = sales.stream().map(Sale::id).toArray(Long[]::new);
    Map<Long, List<SaleLine>> lines = new HashMap<>();
    jdbc.sql("SELECT sale_id, product_id, sku, name, short_name, unit, factor, qty, unit_price, list_price, item_discount, bill_discount,"
            + " manual_discount, amount, vat_type, item_type, vat, promotion_ids, free_of_promotion_id, serial, warranty_months, cogs"
            + " FROM dt_sale_lines WHERE sale_id = ANY (:ids) ORDER BY sale_id, line_index")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          long freeOf = rs.getLong("free_of_promotion_id");
          Long freeOfPromotionId = rs.wasNull() ? null : freeOf;
          lines.computeIfAbsent(rs.getLong("sale_id"), k -> new ArrayList<>()).add(new SaleLine(
              rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getString("short_name"), rs.getString("unit"),
              rs.getInt("factor"), rs.getInt("qty"), dbl(rs, "unit_price"), dbl(rs, "list_price"), dbl(rs, "item_discount"),
              dbl(rs, "bill_discount"), dbl(rs, "manual_discount"), dbl(rs, "amount"), rs.getString("vat_type"), rs.getString("item_type"),
              dbl(rs, "vat"), ids(rs, "promotion_ids"), freeOfPromotionId, rs.getString("serial"), rs.getInt("warranty_months"),
              dbl(rs, "cogs")));
        });
    Map<Long, List<SalePayment>> payments = new HashMap<>();
    jdbc.sql("SELECT sale_id, method_id, name, type, amount, tendered, reference, installment_months FROM dt_sale_payments"
            + " WHERE sale_id = ANY (:ids) ORDER BY sale_id, position")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          int months = rs.getInt("installment_months");
          Integer installmentMonths = rs.wasNull() ? null : months;
          payments.computeIfAbsent(rs.getLong("sale_id"), k -> new ArrayList<>()).add(new SalePayment(
              rs.getLong("method_id"), rs.getString("name"), rs.getString("type"), dbl(rs, "amount"), dbl(rs, "tendered"),
              rs.getString("reference"), installmentMonths));
        });
    // ใบลดหนี้ (เก่าก่อน) + หน่วยที่คืนแล้วต่อบรรทัด + ใบเปลี่ยน
    Map<Long, List<String>> noteNos = new HashMap<>();
    Map<Long, Double> credited = new HashMap<>();
    jdbc.sql("SELECT sale_id, cn_no, total FROM dt_credit_notes WHERE sale_id = ANY (:ids) ORDER BY date, id")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          noteNos.computeIfAbsent(rs.getLong("sale_id"), k -> new ArrayList<>()).add(rs.getString("cn_no"));
          credited.merge(rs.getLong("sale_id"), rs.getBigDecimal("total").doubleValue(), Double::sum);
        });
    Map<Long, Map<Integer, Integer>> returned = new HashMap<>();
    jdbc.sql("SELECT n.sale_id, l.sale_line_index, sum(l.qty) AS qty FROM dt_credit_note_lines l"
            + " JOIN dt_credit_notes n ON n.id = l.credit_note_id WHERE n.sale_id = ANY (:ids) GROUP BY n.sale_id, l.sale_line_index")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          returned.computeIfAbsent(rs.getLong("sale_id"), k -> new HashMap<>()).put(rs.getInt("sale_line_index"), rs.getInt("qty"));
        });
    Map<Long, List<String>> exchangeNos = new HashMap<>();
    jdbc.sql("SELECT sale_id, ex_no FROM dt_exchanges WHERE sale_id = ANY (:ids) ORDER BY date, id")
        .param("ids", ids)
        .query((ResultSet rs) -> {
          exchangeNos.computeIfAbsent(rs.getLong("sale_id"), k -> new ArrayList<>()).add(rs.getString("ex_no"));
        });
    return sales.stream().map(s -> {
      List<SaleLine> own = List.copyOf(lines.getOrDefault(s.id(), List.of()));
      List<String> notes = List.copyOf(noteNos.getOrDefault(s.id(), List.of()));
      Map<Integer, Integer> back = returned.getOrDefault(s.id(), Map.of());
      boolean full = true;
      for (int i = 0; i < own.size(); i++) {
        if (back.getOrDefault(i, 0) < own.get(i).qty()) {
          full = false;
        }
      }
      String returnStatus = notes.isEmpty() ? "none" : full ? "full" : "partial";
      return new Sale(s.id(), s.orderNo(), s.date(), s.cashier(), s.customer(),
        own, List.copyOf(payments.getOrDefault(s.id(), List.of())), s.subtotal(),
        s.itemDiscount(), s.billDiscount(), s.total(), s.vat(), s.rounding(), s.change(), s.billPromotionIds(), s.itemCount(), s.status(),
        s.voidedAt(), s.voidReason(), s.taxInvoiceNo(), returnStatus, Money.round2(credited.getOrDefault(s.id(), 0.0)), notes,
        List.copyOf(exchangeNos.getOrDefault(s.id(), List.of())),
        s.manualDiscount(), s.manualDiscountReason(), s.manualDiscountBy(), s.shiftId(), s.voidShiftId(), s.voidedBy(),
        s.voidApprovedBy(), s.branchId(), s.terminalId(), s.posId(), s.vatRegistered());
    }).toList();
  }

  private static Sale row(ResultSet rs, int i) throws SQLException {
    long shift = rs.getLong("shift_id");
    Long shiftId = rs.wasNull() ? null : shift;
    long voidShift = rs.getLong("void_shift_id");
    Long voidShiftId = rs.wasNull() ? null : voidShift;
    int terminal = rs.getInt("terminal_id");
    Integer terminalId = rs.wasNull() ? null : terminal;
    return new Sale(rs.getLong("id"), rs.getString("order_no"), instant(rs.getTimestamp("sold_at")), rs.getString("cashier"),
        rs.getString("customer"), List.of(), List.of(), dbl(rs, "subtotal"), dbl(rs, "item_discount"), dbl(rs, "bill_discount"),
        dbl(rs, "total"), dbl(rs, "vat"), dbl(rs, "rounding"), dbl(rs, "change"), ids(rs, "bill_promotion_ids"), rs.getInt("item_count"),
        rs.getString("status"), instant(rs.getTimestamp("voided_at")), rs.getString("void_reason"), rs.getString("tax_invoice_no"),
        "none", 0, List.of(), List.of(), dbl(rs, "manual_discount"), rs.getString("manual_discount_reason"),
        rs.getString("manual_discount_by"), shiftId, voidShiftId, rs.getString("voided_by"), rs.getString("void_approved_by"),
        rs.getLong("branch_id"), terminalId, rs.getString("pos_id"), rs.getBoolean("vat_registered"));
  }

  private static double dbl(ResultSet rs, String column) throws SQLException {
    return rs.getBigDecimal(column).doubleValue();
  }

  private static List<Long> ids(ResultSet rs, String column) throws SQLException {
    return Arrays.asList((Long[]) rs.getArray(column).getArray());
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
