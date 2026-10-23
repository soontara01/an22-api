package com.an22.api.shift;

import com.an22.api.model.CashMovement;
import com.an22.api.model.DenominationCount;
import com.an22.api.model.DrawerOpen;
import com.an22.api.model.Shift;
import com.an22.api.model.ShiftAdjustment;
import com.an22.api.model.ShiftCount;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * dt_shifts (+ ยอดนับ) และสิ่งที่เกิดในกะ (เงินเข้าออก, เปิดลิ้นชัก, ปรับปรุงหลังปิด) ของบริษัทใน transaction — วันของกะ = วันเปิดตามเวลา
 * Asia/Bangkok (S5).
 */
@Repository
public class ShiftDao {

  /** กะ + เจ้าของ (id ผู้ใช้ — ไม่ส่งออก API). */
  public record Stored(Shift shift, long cashierId) {}

  private static final String SELECT =
      "SELECT s.id, s.shift_no, s.terminal_id, s.branch_id, s.pos_id, s.cashier_user_id, s.cashier, s.status, s.opened_at,"
          + " s.opening_float, s.opening_denominations, s.closed_at, s.closed_by, s.cash_denominations, s.deposit_amount, s.float_left,"
          + " s.approval_status, s.approved_by, s.approved_at, s.note, s.locked_at,"
          + " (SELECT sum(a.amount) FROM dt_shift_adjustments a WHERE a.shift_id = s.id) AS adjustment_total FROM dt_shifts s";
  private static final TypeReference<List<DenominationCount>> DENOMINATIONS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  ShiftDao(JdbcClient appJdbc, JsonMapper json) {
    this.jdbc = appJdbc;
    this.json = json;
  }

  // ---------- กะ ----------

  public long insert(String shiftNo, int terminalId, long branchId, String posId, long cashierId, String cashier, double openingFloat,
      List<DenominationCount> denominations) {
    return jdbc.sql(
            "INSERT INTO dt_shifts (tenant_id, shift_no, terminal_id, branch_id, pos_id, cashier_user_id, cashier, status, opening_float,"
                + " opening_denominations) VALUES (app_tenant_id(), :no, :terminal, :branch, :posId, :cashierId, :cashier, 'open', :float,"
                + " CAST(:denominations AS jsonb)) RETURNING id")
        .param("no", shiftNo).param("terminal", terminalId).param("branch", branchId).param("posId", posId)
        .param("cashierId", cashierId).param("cashier", cashier).param("float", BigDecimal.valueOf(openingFloat))
        .param("denominations", json.writeValueAsString(denominations))
        .query(Long.class)
        .single();
  }

  public Optional<Stored> find(long id) {
    return withCounts(jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(this::row).list()).stream().findFirst();
  }

  /** อ่านพร้อมล็อกแถวจน commit (เขียนกะทีละ request). */
  public Optional<Stored> lock(long id) {
    jdbc.sql("SELECT id FROM dt_shifts WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
    return find(id);
  }

  /** กะเปิดของเครื่อง (มีได้ 1). */
  public Optional<Stored> openOfTerminal(int terminalId) {
    return withCounts(jdbc.sql(SELECT + " WHERE s.terminal_id = :terminal AND s.status = 'open'").param("terminal", terminalId)
        .query(this::row).list()).stream().findFirst();
  }

  public boolean userHasOpenShift(long userId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_shifts WHERE cashier_user_id = :user AND status = 'open')")
        .param("user", userId).query(Boolean.class).single();
  }

  public int openCount() {
    return jdbc.sql("SELECT count(*) FROM dt_shifts WHERE status = 'open'").query(Integer.class).single();
  }

  public boolean openInBranch(long branchId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_shifts s JOIN dt_pos_terminals t ON t.id = s.terminal_id"
            + " WHERE t.branch_id = :branch AND s.status = 'open')")
        .param("branch", branchId).query(Boolean.class).single();
  }

  public boolean terminalUsed(int terminalId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dt_shifts WHERE terminal_id = :terminal)")
        .param("terminal", terminalId).query(Boolean.class).single();
  }

  /**
   * กะที่เปิดในช่วงวัน (วันท้องถิ่นของ opened_at) ของสาขา (null = ทุกสาขา) / ของผู้ใช้ (null = ทุกคน) ใหม่สุดก่อน — ไม่เกิน {@code limit} กะ.
   */
  public List<Stored> list(LocalDate from, LocalDate to, Long branchId, Long cashierId, boolean openOnly, int limit) {
    return withCounts(jdbc.sql(SELECT + " WHERE (s.opened_at AT TIME ZONE 'Asia/Bangkok')::date BETWEEN :from AND :to"
            + " AND (CAST(:branch AS bigint) IS NULL OR s.branch_id = :branch)"
            + " AND (CAST(:cashier AS bigint) IS NULL OR s.cashier_user_id = :cashier)"
            + " AND (NOT :openOnly OR s.status = 'open') ORDER BY s.opened_at DESC, s.id DESC LIMIT :limit")
        .param("from", from).param("to", to).param("branch", branchId).param("cashier", cashierId).param("openOnly", openOnly)
        .param("limit", limit)
        .query(this::row).list());
  }

  /** เงินที่กะปิดล่าสุดของเครื่องทิ้งไว้ในลิ้นชัก (เงินทอนตั้งต้นของกะใหม่). */
  public double lastFloat(int terminalId) {
    return jdbc.sql("SELECT float_left FROM dt_shifts WHERE terminal_id = :terminal AND status = 'closed'"
            + " ORDER BY opened_at DESC, id DESC LIMIT 1")
        .param("terminal", terminalId).query(BigDecimal.class).optional().map(BigDecimal::doubleValue).orElse(0.0);
  }

  /** E48: ล็อกหน้าจอ (คงเวลาเดิมถ้าล็อกอยู่แล้ว) / ปลดล็อก. */
  public void setLocked(long id, boolean locked) {
    jdbc.sql(locked
            ? "UPDATE dt_shifts SET locked_at = coalesce(locked_at, now()) WHERE id = :id"
            : "UPDATE dt_shifts SET locked_at = NULL WHERE id = :id")
        .param("id", id).update();
  }

  public void close(long id, String closedBy, List<ShiftCount> counts, List<DenominationCount> cashDenominations, double deposit,
      double floatLeft, String approvalStatus, String approvedBy, String note) {
    jdbc.sql("UPDATE dt_shifts SET status = 'closed', locked_at = NULL, closed_at = now(), closed_by = :by,"
            + " cash_denominations = CAST(:denominations AS jsonb), deposit_amount = :deposit, float_left = :floatLeft,"
            + " approval_status = :approval, approved_by = :approvedBy,"
            + " approved_at = CASE WHEN :approval = 'approved' THEN now() END, note = :note WHERE id = :id")
        .param("by", closedBy).param("denominations", json.writeValueAsString(cashDenominations))
        .param("deposit", BigDecimal.valueOf(deposit)).param("floatLeft", BigDecimal.valueOf(floatLeft))
        .param("approval", approvalStatus).param("approvedBy", approvedBy).param("note", note).param("id", id)
        .update();
    for (int i = 0; i < counts.size(); i++) {
      ShiftCount c = counts.get(i);
      jdbc.sql("INSERT INTO dt_shift_counts (tenant_id, shift_id, position, method_id, name, expected, counted, close_check, confirmed_by,"
              + " first_count, reason_code, reason) VALUES (app_tenant_id(), :shift, :pos, :method, :name, :expected, :counted, :check,"
              + " :confirmedBy, :first, :reasonCode, :reason)")
          .param("shift", id).param("pos", i).param("method", c.methodId()).param("name", c.name())
          .param("expected", BigDecimal.valueOf(c.expected())).param("counted", decimal(c.counted())).param("check", c.closeCheck())
          .param("confirmedBy", c.confirmedBy()).param("first", decimal(c.firstCount())).param("reasonCode", c.reasonCode())
          .param("reason", c.reason())
          .update();
    }
  }

  public void approve(long id, String by) {
    jdbc.sql("UPDATE dt_shifts SET approval_status = 'approved', approved_by = :by, approved_at = now() WHERE id = :id")
        .param("by", by).param("id", id).update();
  }

  // ---------- เงินเข้าออก / เปิดลิ้นชัก / ปรับปรุง ----------

  public long insertMovement(long shiftId, String type, double amount, String reason, String by) {
    return jdbc.sql("INSERT INTO dt_cash_movements (tenant_id, shift_id, type, amount, reason, by_name)"
            + " VALUES (app_tenant_id(), :shift, :type, :amount, :reason, :by) RETURNING id")
        .param("shift", shiftId).param("type", type).param("amount", BigDecimal.valueOf(amount)).param("reason", reason)
        .param("by", by)
        .query(Long.class).single();
  }

  public List<CashMovement> movements(long shiftId) {
    return jdbc.sql("SELECT id, shift_id, type, amount, reason, by_name, created_at FROM dt_cash_movements WHERE shift_id = :shift"
            + " ORDER BY id")
        .param("shift", shiftId)
        .query((rs, i) -> new CashMovement(rs.getLong("id"), rs.getLong("shift_id"), rs.getString("type"),
            rs.getBigDecimal("amount").doubleValue(), rs.getString("reason"), rs.getString("by_name"),
            rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  public long insertDrawerOpen(long shiftId, String reason, String by) {
    return jdbc.sql("INSERT INTO dt_drawer_opens (tenant_id, shift_id, reason, by_name) VALUES (app_tenant_id(), :shift, :reason, :by)"
            + " RETURNING id")
        .param("shift", shiftId).param("reason", reason).param("by", by)
        .query(Long.class).single();
  }

  public List<DrawerOpen> drawerOpens(long shiftId) {
    return jdbc.sql("SELECT id, shift_id, reason, by_name, created_at FROM dt_drawer_opens WHERE shift_id = :shift ORDER BY id")
        .param("shift", shiftId)
        .query((rs, i) -> new DrawerOpen(rs.getLong("id"), rs.getLong("shift_id"), rs.getString("reason"), rs.getString("by_name"),
            rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /** shiftId ของการเปิดลิ้นชักแต่ละครั้งของกะที่ระบุ (นับใน summary / รายงาน). */
  public List<Long> drawerOpenShiftIds(List<Long> shiftIds) {
    return jdbc.sql("SELECT shift_id FROM dt_drawer_opens WHERE shift_id = ANY (:ids)")
        .param("ids", shiftIds.toArray(Long[]::new)).query(Long.class).list();
  }

  public long insertAdjustment(long shiftId, long methodId, String name, double amount, String reasonCode, String reason, String by) {
    return jdbc.sql("INSERT INTO dt_shift_adjustments (tenant_id, shift_id, method_id, name, amount, reason_code, reason, by_name)"
            + " VALUES (app_tenant_id(), :shift, :method, :name, :amount, :code, :reason, :by) RETURNING id")
        .param("shift", shiftId).param("method", methodId).param("name", name).param("amount", BigDecimal.valueOf(amount))
        .param("code", reasonCode).param("reason", reason).param("by", by)
        .query(Long.class).single();
  }

  public List<ShiftAdjustment> adjustments(long shiftId) {
    return jdbc.sql("SELECT id, shift_id, method_id, name, amount, reason_code, reason, by_name, created_at FROM dt_shift_adjustments"
            + " WHERE shift_id = :shift ORDER BY id")
        .param("shift", shiftId)
        .query((rs, i) -> new ShiftAdjustment(rs.getLong("id"), rs.getLong("shift_id"), rs.getLong("method_id"), rs.getString("name"),
            rs.getBigDecimal("amount").doubleValue(), rs.getString("reason_code"), rs.getString("reason"), rs.getString("by_name"),
            rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  public List<ShiftRules.AdjustmentRef> adjustmentRefs(List<Long> shiftIds) {
    return jdbc.sql("SELECT shift_id, method_id, amount FROM dt_shift_adjustments WHERE shift_id = ANY (:ids) ORDER BY id")
        .param("ids", shiftIds.toArray(Long[]::new))
        .query((rs, i) -> new ShiftRules.AdjustmentRef(rs.getLong("shift_id"), rs.getLong("method_id"),
            rs.getBigDecimal("amount").doubleValue()))
        .list();
  }

  // ---------- แถว ----------

  private List<Stored> withCounts(List<Stored> shifts) {
    if (shifts.isEmpty()) {
      return shifts;
    }
    Map<Long, List<ShiftCount>> counts = new HashMap<>();
    jdbc.sql("SELECT shift_id, method_id, name, expected, counted, close_check, confirmed_by, first_count, reason_code, reason"
            + " FROM dt_shift_counts WHERE shift_id = ANY (:ids) ORDER BY shift_id, position")
        .param("ids", shifts.stream().map(s -> s.shift().id()).toArray(Long[]::new))
        .query((ResultSet rs) -> {
          counts.computeIfAbsent(rs.getLong("shift_id"), k -> new ArrayList<>()).add(new ShiftCount(
              rs.getLong("method_id"), rs.getString("name"), rs.getBigDecimal("expected").doubleValue(), dbl(rs, "counted"),
              rs.getString("close_check"), rs.getString("confirmed_by"), dbl(rs, "first_count"), rs.getString("reason_code"),
              rs.getString("reason")));
        });
    return shifts.stream().map(st -> {
      Shift s = st.shift();
      return new Stored(new Shift(s.id(), s.shiftNo(), s.terminalId(), s.branchId(), s.posId(), s.cashier(), s.status(), s.openedAt(),
          s.openingFloat(), s.openingDenominations(), s.closedAt(), s.closedBy(), List.copyOf(counts.getOrDefault(s.id(), List.of())),
          s.cashDenominations(), s.depositAmount(), s.floatLeft(), s.approvalStatus(), s.approvedBy(), s.approvedAt(), s.note(),
          s.lockedAt(), s.adjustmentTotal()), st.cashierId());
    }).toList();
  }

  private Stored row(ResultSet rs, int i) throws SQLException {
    Shift s = new Shift(rs.getLong("id"), rs.getString("shift_no"), rs.getInt("terminal_id"), rs.getLong("branch_id"),
        rs.getString("pos_id"), rs.getString("cashier"), rs.getString("status"), instant(rs.getTimestamp("opened_at")),
        rs.getBigDecimal("opening_float").doubleValue(), json.readValue(rs.getString("opening_denominations"), DENOMINATIONS),
        instant(rs.getTimestamp("closed_at")), rs.getString("closed_by"), List.of(),
        json.readValue(rs.getString("cash_denominations"), DENOMINATIONS), rs.getBigDecimal("deposit_amount").doubleValue(),
        rs.getBigDecimal("float_left").doubleValue(), rs.getString("approval_status"), rs.getString("approved_by"),
        instant(rs.getTimestamp("approved_at")), rs.getString("note"), instant(rs.getTimestamp("locked_at")), dbl(rs, "adjustment_total"));
    return new Stored(s, rs.getLong("cashier_user_id"));
  }

  private static Double dbl(ResultSet rs, String column) throws SQLException {
    BigDecimal value = rs.getBigDecimal(column);
    return value == null ? null : value.doubleValue();
  }

  private static BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
