package com.an22.api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link Mailer} ชั่วคราว: log ว่า "ส่งแล้ว" เท่านั้น — ไม่ log เนื้อหา (ลิงก์ / token) และอีเมลแบบ mask ({@code s***@example.com}).
 * แทนด้วยผู้ให้บริการจริงทีหลัง (bean อื่นที่ implement {@link Mailer}).
 */
@Component
class LoggingMailer implements Mailer {

  private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

  @Override
  public void send(String to, String subject, String body) {
    log.info("อีเมล (ยังไม่ส่งจริง) ถึง {} เรื่อง \"{}\"", mask(to), subject);
  }

  /** {@code somchai@example.com} → {@code s***@example.com}. */
  static String mask(String email) {
    int at = email == null ? -1 : email.indexOf('@');
    return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
  }
}
