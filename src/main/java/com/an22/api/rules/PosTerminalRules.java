package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.PosTerminal;
import java.util.List;

/** port จาก {@code core/models/pos-terminal.model.ts} (T1–T5, E8). */
public final class PosTerminalRules {

  private PosTerminalRules() {}

  /** {@code normalizePosTerminal()} ทับค่าเดิม ({@code base}): ตัดช่องว่างชื่อ / POS ID. */
  public static PosTerminal normalize(PosTerminal base, PosTerminal in) {
    String name = in.name() != null ? in.name() : base.name();
    String posId = in.posId() != null ? in.posId() : base.posId();
    return new PosTerminal(
        in.id() != null ? in.id() : base.id(),
        name.strip(),
        posId.strip(),
        in.branchId() != null ? in.branchId() : base.branchId(),
        in.active() != null ? in.active() : base.active());
  }

  /** {@code POS_TERMINAL_DEFAULTS}. */
  public static PosTerminal defaults() {
    return new PosTerminal(1, "", "", 1L, true);
  }

  /** {@code posTerminalError()}: {@code others} = เครื่องอื่นของบริษัท; เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน. */
  public static String posTerminalError(PosTerminal t, List<PosTerminal> others, List<Branch> branches) {
    if (t.id() == null || t.id() <= 0 || t.id() > 999) {
      return "เลขเครื่องต้องเป็นจำนวนเต็ม 1–999";
    }
    if (others.stream().anyMatch(o -> o.id().equals(t.id()))) {
      return "มีเครื่อง " + t.id() + " อยู่แล้ว";
    }
    String name = t.name().strip();
    if (name.isEmpty()) {
      return "กรุณากรอกชื่อเครื่อง";
    }
    if (name.length() > 40) {
      return "ชื่อเครื่องต้องไม่เกิน 40 ตัวอักษร";
    }
    String posId = t.posId().strip();
    if (posId.length() > 30) {
      return "POS ID ต้องไม่เกิน 30 ตัวอักษร";
    }
    if (!posId.isEmpty() && others.stream().anyMatch(o -> o.posId().strip().equals(posId))) {
      return "POS ID " + posId + " ใช้กับเครื่องอื่นแล้ว";
    }
    Branch branch = branches.stream().filter(b -> t.branchId() != null && b.id() == t.branchId()).findFirst().orElse(null);
    if (branch == null) {
      return "กรุณาเลือกสาขาของเครื่อง";
    }
    if (t.active() && !branch.active()) {
      return "สาขานี้ปิดใช้งานแล้ว เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน";
    }
    return null;
  }

  /**
   * {@code terminalError()} (T2 / E8): อุปกรณ์ต้องตั้งเป็นเครื่องที่ใช้งานอยู่ของทะเบียน อยู่ในสาขาที่ทำงาน ({@code branchId} null = ไม่ตรวจ).
   */
  public static String terminalError(Integer id, List<PosTerminal> terminals, Long branchId, List<Branch> branches) {
    if (id == null) {
      return "เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า";
    }
    PosTerminal terminal = terminals.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    if (terminal == null) {
      return "ไม่พบเครื่อง " + id + " ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่";
    }
    if (!terminal.active()) {
      return "เครื่อง " + id + " ถูกปิดใช้งาน กรุณาให้ผู้ดูแลตั้งค่าใหม่";
    }
    if (branchId != null && !terminal.branchId().equals(branchId)) {
      Branch own = branches.stream().filter(b -> b.id() == terminal.branchId()).findFirst().orElse(null);
      return own != null
          ? "เครื่อง " + id + " เป็นเครื่องของสาขา " + own.code() + " · " + own.name()
              + " — สลับไปสาขานั้น หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่"
          : "เครื่อง " + id + " เป็นเครื่องของสาขาอื่น — สลับไปสาขาของเครื่อง หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่";
    }
    return null;
  }

  /** {@code terminalOrderPrefix()} (T4 / E21): {@code POS-<สาขา>-<เครื่อง>-YYYYMMDD-}. */
  public static String terminalOrderPrefix(String branchCode, int terminalId, String day) {
    return "POS-" + branchCode + "-" + terminalId + "-" + day.replace("-", "") + "-";
  }
}
