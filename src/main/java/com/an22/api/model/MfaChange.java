package com.an22.api.model;

/**
 * {@code MfaChange} (auth.model.ts, E58): เปลี่ยนเครื่อง Authenticator ของตัวเอง — QR ของเครื่องใหม่ + {@code change} (JWT aud=mfa-change
 * อายุ 10 นาที, secret ใหม่เข้ารหัสอยู่ข้างใน) ที่ส่งกลับมาพร้อมรหัสแรกของเครื่องใหม่.
 */
public record MfaChange(String change, MfaSetup setup) {}
