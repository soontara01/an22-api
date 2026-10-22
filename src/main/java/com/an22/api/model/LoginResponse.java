package com.an22.api.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * {@code POST /auth/login}: session + {@code token} — an22-web เก็บ token ไว้ใน session ของมันแล้วตัดออกก่อนถึง browser (E48).
 */
public record LoginResponse(String token, @JsonUnwrapped Session session) {}
