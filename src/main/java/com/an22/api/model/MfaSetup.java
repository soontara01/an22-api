package com.an22.api.model;

/** {@code MfaSetup} (auth.model.ts, E55): secret base32 ให้พิมพ์เอง + URL {@code otpauth://} ที่ QR เก็บ — ส่งจนกว่ารหัสแรกจะยืนยัน. */
public record MfaSetup(String secret, String otpauthUrl) {}
