package com.an22.api.web;

/**
 * {@code X-Terminal-Id} ของ request ตามที่ส่งมา (เลขเครื่อง POS — จำนวนเต็ม > 0, อื่น = ไม่มี) — ยังไม่ตรวจกับทะเบียน (POS / กะตรวจด้วย
 * {@code terminalError()}); เลขที่เป็นเครื่องของบริษัทแล้วอยู่ใน {@code ShopSession.terminalId()} (audit).
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
