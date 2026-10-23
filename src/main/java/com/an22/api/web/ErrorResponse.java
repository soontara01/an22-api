package com.an22.api.web;

/** รูปแบบ error เดียวของ api: {@code { "message": "<ไทย>" }}. */
public record ErrorResponse(String message) {

  /** 404 / 405 — path ไม่รวม {@code /api} (เหมือน mock). */
  public static String noEndpoint(String method, String path) {
    return "ไม่มี endpoint " + method + " " + path;
  }

  public static final String BAD_REQUEST = "ข้อมูลที่ส่งมาไม่ถูกต้อง";
  public static final String SERVER_ERROR = "ระบบขัดข้อง กรุณาลองใหม่";
  /** 503 เมื่อ DB เกิน statement_timeout ของ role (57014) — ยืนยันกับผู้ใช้ 2026-10-23. */
  public static final String DB_TIMEOUT = "ระบบใช้เวลานานเกินไป กรุณาลองใหม่หรือลดช่วงข้อมูล";
  /** 401 ทุกกรณีนอก login — ข้อความเดียวกับ {@code SESSION_EXPIRED} ของ mock. */
  public static final String SESSION_EXPIRED = "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่";
}
