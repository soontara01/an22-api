package com.an22.api.support;

import com.an22.api.mail.Mailer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** {@link Mailer} ของ test: เก็บอีเมลที่ "ส่ง" ไว้ให้ test อ่าน (แทน LoggingMailer ใน context เดียวกัน — ไม่สร้าง context ใหม่). */
@Component
@Primary
public class TestMailer implements Mailer {

  public record Mail(String to, String subject, String body) {}

  private final List<Mail> sent = new ArrayList<>();

  @Override
  public synchronized void send(String to, String subject, String body) {
    sent.add(new Mail(to, subject, body));
  }

  /** อีเมลทั้งหมดที่ส่งถึง {@code to}. */
  public synchronized List<Mail> to(String to) {
    return sent.stream().filter(m -> m.to().equals(to)).toList();
  }
}
