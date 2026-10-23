package com.an22.api.model;

import java.util.List;

/** body ขาเข้าของ {@code /shifts/*} (shift.model.ts) — ตัวเลขเป็น Double ให้กฎตรวจแบบ JS ได้ตรง (field ที่ไม่ส่ง = null). */
public final class ShiftPayloads {

  private ShiftPayloads() {}

  /** {@code ShiftOpenPayload}: terminalId ถ้าส่งต้องตรงกับ {@code X-Terminal-Id}. */
  public record Open(Long terminalId, Double openingFloat, List<DenominationCount> openingDenominations) {}

  /** {@code CashMovementPayload}. */
  public record CashMove(String type, Double amount, String reason) {}

  /** {@code DrawerOpenPayload}. */
  public record DrawerOpenReason(String reason) {}

  /** {@code ShiftCountInput}. */
  public record CountInput(Long methodId, Double counted, Double firstCount, Boolean confirmed, String reasonCode, String reason) {}

  /** {@code { counts }} ของ count-check. */
  public record Counts(List<CountInput> counts) {}

  /** {@code ApprovalCredentials}: login ของ admin ที่พิมพ์ที่เครื่อง (server ตรวจ). */
  public record Credentials(String username, String password) {}

  /** {@code ShiftClosePayload}. */
  public record Close(
      List<CountInput> counts, List<DenominationCount> cashDenominations, Double depositAmount, String note, Credentials approval) {}

  /** {@code ShiftAdjustmentPayload}. */
  public record Adjust(Long methodId, Double amount, String reasonCode, String reason) {}
}
