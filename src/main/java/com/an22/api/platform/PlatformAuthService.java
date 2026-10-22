package com.an22.api.platform;

import com.an22.api.auth.LoginThrottle;
import com.an22.api.auth.ThrottleKey;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.model.PlatformLoginResponse;
import com.an22.api.platform.PlatformAdminDao.AdminRow;
import com.an22.api.rules.UserRules;
import com.an22.api.security.JwtService;
import com.an22.api.web.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** login / session ของ platform admin (E36) — token แยก ({@code aud = platform}). */
@Service
public class PlatformAuthService {

  public static final String WRONG_LOGIN = "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง";

  private final PlatformAdminDao dao;
  private final LoginThrottle throttle;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final String dummyHash;

  PlatformAuthService(PlatformAdminDao dao, LoginThrottle throttle, PasswordEncoder passwords, JwtService jwt) {
    this.dao = dao;
    this.throttle = throttle;
    this.passwords = passwords;
    this.jwt = jwt;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record LoginRequest(String username, String password) {}

  /** {@code POST /platform/auth/login}: ผิด → 401 ข้อความเดียว. */
  @Transactional(value = "platformTx", readOnly = true)
  public PlatformLoginResponse login(LoginRequest req, String ip) {
    String username = UserRules.normalizeUsername(req.username());
    ThrottleKey account = ThrottleKey.platform(username);
    List<ThrottleKey> keys = List.of(account, ThrottleKey.ip(ip));
    throttle.check(keys);
    Optional<AdminRow> row = username.isEmpty() ? Optional.empty() : dao.byUsername(username);
    String raw = req.password() == null ? "" : req.password();
    boolean match = passwords.matches(raw, row.map(AdminRow::passwordHash).orElse(dummyHash));
    if (row.isEmpty() || !match) {
      throttle.fail(keys, null);
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_LOGIN);
    }
    throttle.succeed(account);
    PlatformAdmin admin = row.get().admin();
    return new PlatformLoginResponse(jwt.issuePlatform(admin.id()), admin);
  }

  /** admin ที่ token ระบุ (PlatformSessionFilter ทุก request) — ไม่มี = 401. */
  @Transactional(value = "platformTx", readOnly = true)
  public Optional<PlatformAdmin> current(long adminId) {
    return dao.byId(adminId).map(AdminRow::admin);
  }
}
