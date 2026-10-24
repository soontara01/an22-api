package com.an22.api.platform;

import com.an22.api.model.PlatformAdmin;
import com.an22.api.platform.PlatformAdminDao.AdminRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ส่วนของ platform login ที่อ่าน DB (platformTx) — แยกจาก {@link PlatformAuthService#login} เพื่อให้ {@code LoginThrottle} (REQUIRES_NEW,
 * pool platform) ถูกเรียกนอก transaction นี้ (ไม่ซ้อน connection จาก pool เดียวกัน — B5).
 */
@Service
class PlatformLoginTx {

  private final PlatformAdminDao dao;
  private final PasswordEncoder passwords;
  /** hash ที่ไม่ตรงกับอะไรเลย — ตรวจเมื่อไม่มีชื่อนี้ เวลาตอบจะได้ไม่บอกว่ามีอยู่. */
  private final String dummyHash;

  PlatformLoginTx(PlatformAdminDao dao, PasswordEncoder passwords) {
    this.dao = dao;
    this.passwords = passwords;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  /** admin ที่ชื่อ ({@code username} normalize แล้ว) + รหัสตรง — ผิด / ไม่มี = empty. */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<PlatformAdmin> verify(String username, String password) {
    Optional<AdminRow> row = username.isEmpty() ? Optional.empty() : dao.byUsername(username);
    String raw = password == null ? "" : password;
    boolean match = passwords.matches(raw, row.map(AdminRow::passwordHash).orElse(dummyHash));
    return row.isPresent() && match ? row.map(AdminRow::admin) : Optional.empty();
  }
}
