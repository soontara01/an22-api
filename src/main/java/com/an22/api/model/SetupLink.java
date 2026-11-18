package com.an22.api.model;

import java.time.Instant;

/**
 * {@code SetupLink} (signup.model.ts, E59): ลิงก์ตั้งรหัสผ่านของ owner ที่ platform คัดลอกส่งเอง — {@code path} = {@code /auth/reset-password?token=…}
 * (หน้าบ้านต่อ origin ของตัวเอง), แสดงครั้งเดียว (DB เก็บแค่ HMAC ของ token).
 */
public record SetupLink(String path, Instant expiresAt) {}
