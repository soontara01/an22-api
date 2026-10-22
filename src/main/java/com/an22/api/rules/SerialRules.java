package com.an22.api.rules;

import java.util.Map;

/** port จาก {@code core/models/serial.model.ts}. */
public final class SerialRules {

  public static final Map<String, String> STATUS_LABEL =
      Map.of("in_stock", "คงคลัง", "in_transit", "ระหว่างโอน", "sold", "ขายแล้ว", "damaged", "ชำรุด/เสียหาย", "other", "อื่นๆ");

  private SerialRules() {}

  /** {@code serialFormatError()}: ตามรูปแบบของ SKU — null = ถูก. */
  public static String serialFormatError(String serial, String prefix, Integer length) {
    if (!serial.matches("[A-Z0-9-]+")) {
      return "ใช้ได้เฉพาะ A-Z, 0-9 และ -";
    }
    if (prefix != null && !prefix.isEmpty() && !serial.startsWith(prefix)) {
      return "ต้องขึ้นต้นด้วย " + prefix;
    }
    if (length != null && length > 0 && serial.length() != length) {
      return "ต้องยาว " + length + " ตัว (ตอนนี้ " + serial.length() + ")";
    }
    return null;
  }
}
