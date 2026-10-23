package com.an22.api.sale;

import com.an22.api.model.Exchange;
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

/** dt_exchanges + บรรทัด ของบริษัทใน transaction — เอกสาร append-only. */
@Repository
public class ExchangeDao {

  private static final String SELECT =
      "SELECT id, ex_no, sale_id, order_no, sale_date, date, cashier, reason, shift_id, branch_id, terminal_id, pos_id FROM dt_exchanges";

  private final JdbcClient jdbc;

  ExchangeDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public long insert(Exchange x) {
    long id = jdbc.sql("INSERT INTO dt_exchanges (tenant_id, ex_no, sale_id, order_no, sale_date, cashier, reason, shift_id, branch_id,"
            + " terminal_id, pos_id) VALUES (app_tenant_id(), :no, :sale, :orderNo, :saleDate, :cashier, :reason, :shift, :branch,"
            + " :terminal, :posId) RETURNING id")
        .param("no", x.exNo()).param("sale", x.saleId()).param("orderNo", x.orderNo()).param("saleDate", Timestamp.from(x.saleDate()))
        .param("cashier", x.cashier()).param("reason", x.reason()).param("shift", x.shiftId()).param("branch", x.branchId())
        .param("terminal", x.terminalId()).param("posId", x.posId())
        .query(Long.class).single();
    for (int i = 0; i < x.lines().size(); i++) {
      Exchange.Line l = x.lines().get(i);
      jdbc.sql("INSERT INTO dt_exchange_lines (tenant_id, exchange_id, position, sale_line_index, product_id, sku, name, short_name, unit,"
              + " factor, qty, old_serial, new_serial, restock, cost_in, cost_out) VALUES (app_tenant_id(), :x, :pos, :index, :product,"
              + " :sku, :name, :shortName, :unit, :factor, :qty, :oldSerial, :newSerial, :restock, :costIn, :costOut)")
          .param("x", id).param("pos", i).param("index", l.saleLineIndex()).param("product", l.productId()).param("sku", l.sku())
          .param("name", l.name()).param("shortName", l.shortName()).param("unit", l.unit()).param("factor", l.factor())
          .param("qty", l.qty()).param("oldSerial", l.oldSerial()).param("newSerial", l.newSerial()).param("restock", l.restock())
          .param("costIn", BigDecimal.valueOf(l.costIn())).param("costOut", BigDecimal.valueOf(l.costOut()))
          .update();
    }
    return id;
  }

  public Optional<Exchange> find(long id) {
    return withLines(jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(ExchangeDao::row).list()).stream().findFirst();
  }

  /** ใบเปลี่ยนของบิล เก่าก่อน. */
  public List<Exchange> ofSale(long saleId) {
    return withLines(jdbc.sql(SELECT + " WHERE sale_id = :sale ORDER BY date, id").param("sale", saleId).query(ExchangeDao::row).list());
  }

  /** shiftId ของใบเปลี่ยนในกะ (นับใน summary). */
  public List<Long> shiftIds(long shiftId) {
    return jdbc.sql("SELECT shift_id FROM dt_exchanges WHERE shift_id = :shift").param("shift", shiftId).query(Long.class).list();
  }

  public boolean terminalUsed(int terminalId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_exchanges WHERE terminal_id = :id)").param("id", terminalId)
        .query(Boolean.class).single();
  }

  private List<Exchange> withLines(List<Exchange> list) {
    if (list.isEmpty()) {
      return list;
    }
    Map<Long, List<Exchange.Line>> lines = new HashMap<>();
    jdbc.sql("SELECT exchange_id, sale_line_index, product_id, sku, name, short_name, unit, factor, qty, old_serial, new_serial, restock,"
            + " cost_in, cost_out FROM dt_exchange_lines WHERE exchange_id = ANY (:ids) ORDER BY exchange_id, position")
        .param("ids", list.stream().map(Exchange::id).toArray(Long[]::new))
        .query((ResultSet rs) -> {
          lines.computeIfAbsent(rs.getLong("exchange_id"), k -> new ArrayList<>()).add(new Exchange.Line(
              rs.getInt("sale_line_index"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"),
              rs.getString("short_name"), rs.getString("unit"), rs.getInt("factor"), rs.getInt("qty"), rs.getString("old_serial"),
              rs.getString("new_serial"), rs.getBoolean("restock"), rs.getBigDecimal("cost_in").doubleValue(),
              rs.getBigDecimal("cost_out").doubleValue()));
        });
    return list.stream().map(x -> new Exchange(x.id(), x.exNo(), x.saleId(), x.orderNo(), x.saleDate(), x.date(), x.cashier(), x.reason(),
        List.copyOf(lines.getOrDefault(x.id(), List.of())), x.shiftId(), x.branchId(), x.terminalId(), x.posId())).toList();
  }

  private static Exchange row(ResultSet rs, int i) throws SQLException {
    long shift = rs.getLong("shift_id");
    Long shiftId = rs.wasNull() ? null : shift;
    int terminal = rs.getInt("terminal_id");
    Integer terminalId = rs.wasNull() ? null : terminal;
    return new Exchange(rs.getLong("id"), rs.getString("ex_no"), rs.getLong("sale_id"), rs.getString("order_no"),
        instant(rs.getTimestamp("sale_date")), instant(rs.getTimestamp("date")), rs.getString("cashier"), rs.getString("reason"),
        List.of(), shiftId, rs.getLong("branch_id"), terminalId, rs.getString("pos_id"));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
