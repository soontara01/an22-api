package com.an22.api.web;

import jakarta.servlet.http.HttpServletRequest;

/** IP ของผู้ใช้ = {@code X-Real-IP} ที่ an22-web ใส่ใหม่ทุก request (api เข้าถึงได้จาก web เท่านั้น — E49); ไม่มี = IP ที่ต่อเข้ามา. */
public final class ClientIp {

  private ClientIp() {}

  public static String of(HttpServletRequest request) {
    String real = request.getHeader("X-Real-IP");
    return real != null && !real.isBlank() ? real.strip() : request.getRemoteAddr();
  }
}
