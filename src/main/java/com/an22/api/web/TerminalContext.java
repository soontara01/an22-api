package com.an22.api.web;

/**
 * {@code X-Terminal-Id} ของ request (เลขเครื่อง POS — จำนวนเต็ม > 0, อื่น = ไม่มี).
 * ยังไม่ได้ตรวจว่าเป็นเครื่องของบริษัท: ตาราง dt_pos_terminals มาในขั้น 4 (audit ใช้ {@code terminalId} ได้เมื่อเป็นเครื่องของบริษัทเท่านั้น).
 */
public record TerminalContext(Long terminalId) {

  public static final String ATTR = TerminalContext.class.getName();

  public static TerminalContext parse(String header) {
    if (header == null) {
      return new TerminalContext(null);
    }
    try {
      long id = Long.parseLong(header.strip());
      return new TerminalContext(id > 0 ? id : null);
    } catch (NumberFormatException e) {
      return new TerminalContext(null);
    }
  }
}
