package com.an22.api.model;

/** {@code CategoryPayload}: code / name / parentId / active (PUT ไม่ย้ายแม่ — ไม่ใช้ parentId). */
public record CategoryPayload(String code, String name, Long parentId, Boolean active) {}
