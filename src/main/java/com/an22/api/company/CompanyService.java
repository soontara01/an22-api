package com.an22.api.company;

import com.an22.api.audit.Audit;
import com.an22.api.auth.ShopSession;
import com.an22.api.db.TenantLocks;
import com.an22.api.model.User;
import com.an22.api.rules.UserRules;
import com.an22.api.user.UserDao;
import com.an22.api.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** บัญชีบริษัท (owner, E33) — ขั้น 3: โอนความเป็นเจ้าของ; แพ็กเกจ / export / ลบ มาในขั้น 11. */
@Service
public class CompanyService {

  private final UserDao users;
  private final TenantLocks locks;
  private final PasswordEncoder passwords;
  private final Audit audit;

  CompanyService(UserDao users, TenantLocks locks, PasswordEncoder passwords, Audit audit) {
    this.users = users;
    this.locks = locks;
    this.passwords = passwords;
    this.audit = audit;
  }

  /**
   * {@code POST /company/owner}: owner ยืนยันด้วยรหัสผ่านของตัวเอง — ไม่ใช่ owner → 403, กฎอื่น → 400, รหัสผิด → 400 (ห้าม 401). ผู้รับ →
   * owner ทุกสาขา, owner เดิม → admin ทุกสาขา. คืนผู้ใช้ทั้งบริษัท.
   */
  @Transactional
  public List<User> transferOwner(ShopSession s, Long userId, String password) {
    locks.lock(TenantLocks.ACCOUNTS);
    User caller = s.user();
    long target = userId == null ? -1 : userId;
    String problem = UserRules.ownerTransferError(users.list(), caller, target);
    if (problem != null) {
      throw new ApiException(UserRules.isOwner(caller.role()) ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN, problem);
    }
    if (password == null || !passwords.matches(password, users.passwordHash(caller.id()))) {
      throw ApiException.badRequest("รหัสผ่านไม่ถูกต้อง");
    }
    User old = users.find(caller.id()).orElseThrow();
    User next = users.find(target).orElseThrow();
    // owner เดิมลงก่อน (owner 1 คนต่อบริษัท — unique index)
    users.update(new User(old.id(), old.username(), old.name(), old.email(), "admin", old.active(), List.of(), true, old.createdAt()));
    users.update(new User(next.id(), next.username(), next.name(), next.email(), "owner", next.active(), List.of(), true, next.createdAt()));
    audit.record(s, "owner_transfer", null, "user", next.id(), next.username(), "โอนความเป็นเจ้าของให้ " + next.name());
    return users.list();
  }
}
