package com.an22.api.model;

/** {@code ShiftCount}: ยอดตอนปิดกะต่อช่องทาง (snapshot ชื่อ / ยอดระบบ / วิธีตรวจ); counted null = ไม่นับ (confirm / none). */
public record ShiftCount(
    long methodId,
    String name,
    double expected,
    Double counted,
    String closeCheck,
    String confirmedBy,
    Double firstCount,
    String reasonCode,
    String reason) {}
