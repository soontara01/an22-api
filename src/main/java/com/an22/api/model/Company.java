package com.an22.api.model;

import java.time.LocalDate;

/**
 * {@code Company} ของ login / me (company.model.ts) — ชื่อ / เลขภาษีมาจาก company_info;
 * {@code deletionPurgeAfter} = วันแรกที่ลบได้เมื่อมีคำขอลบ (E39), null = ไม่มี.
 */
public record Company(long id, String code, String name, String taxId, boolean active, LocalDate deletionPurgeAfter) {}
