package com.an22.api.mail;

/** ส่งอีเมล (ขั้นนี้ยังไม่มีผู้ให้บริการจริง — แผนหัวข้อ "เครื่องมือ": เลื่อนไปทีหลัง). */
public interface Mailer {

  /** ส่ง {@code body} ถึง {@code to} — ตัวที่ใช้จริงต้องไม่ log เนื้อหา (มีลิงก์ / token). */
  void send(String to, String subject, String body);
}
