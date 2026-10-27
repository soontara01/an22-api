package com.an22.api.user;

import com.an22.api.audit.Audit;
import com.an22.api.auth.PasswordResetTokens;
import com.an22.api.auth.ShopSession;
import com.an22.api.branch.BranchDao;
import com.an22.api.company.PlanLimits;
import com.an22.api.db.TenantLocks;
import com.an22.api.model.User;
import com.an22.api.model.UserPayload;
import com.an22.api.rules.AuditRules;
import com.an22.api.rules.PlanRules.LimitKind;
import com.an22.api.rules.UserRules;
import com.an22.api.rules.UserRules.Rights;
import com.an22.api.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /users} (admin เท่านั้น — controller ตรวจ) — ลำดับตรวจตาม mock: scope (403) → กฎ (400) → แพ็กเกจ (403). ไม่มี endpoint ไหน
 * คืนรหัสผ่าน.
 */
@Service
public class UserService {

  static final String NOT_FOUND = "ไม่พบผู้ใช้";
  static final Map<String, String> ROLE_LABEL =
      Map.of("owner", "เจ้าของบริษัท", "admin", "ผู้ดูแลระบบ", "user", "ผู้ใช้ทั่วไป");

  private final UserDao dao;
  private final BranchDao branches;
  private final TenantLocks locks;
  private final PlanLimits limits;
  private final PasswordEncoder passwords;
  private final PasswordResetTokens tokens;
  private final Audit audit;

  UserService(
      UserDao dao,
      BranchDao branches,
      TenantLocks locks,
      PlanLimits limits,
      PasswordEncoder passwords,
      PasswordResetTokens tokens,
      Audit audit) {
    this.dao = dao;
    this.branches = branches;
    this.locks = locks;
    this.limits = limits;
    this.passwords = passwords;
    this.tokens = tokens;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<User> list() {
    return dao.list();
  }

  @Transactional(readOnly = true)
  public User get(long id) {
    return find(id);
  }

  @Transactional
  public User create(ShopSession s, UserPayload in) {
    locks.lock(TenantLocks.ACCOUNTS);
    User user = withUserRules(new User(0, in.username(), in.name(), in.email(), in.role(),
        in.active() == null || in.active(), in.branchIds() == null ? List.of() : in.branchIds(),
        in.allBranches() != null && in.allBranches(), null));
    scope(s, null, Rights.of(user));
    List<User> users = dao.list();
    String password = in.password() == null ? "" : in.password();
    badRequest(first(
        UserRules.ownerRoleError(null, user.role()),
        UserRules.userIdentityError(users, user.username(), user.email(), user.role(), null),
        UserRules.userBranchError(user.allBranches(), user.branchIds(), branches.list()),
        UserRules.passwordError(password),
        knownRole(user.role())));
    limits.check(s, LimitKind.USERS, user.active(), false);
    User saved = find(dao.insert(user, passwords.encode(password)));
    record(s, saved, "เพิ่ม");
    return saved;
  }

  /** {@code PUT /users/:id}: ทับเฉพาะ field ที่ส่ง. */
  @Transactional
  public User update(ShopSession s, long id, UserPayload in) {
    locks.lock(TenantLocks.ACCOUNTS);
    User current = find(id);
    User next = withUserRules(new User(
        id,
        in.username() != null ? in.username() : current.username(),
        in.name() != null ? in.name() : current.name(),
        in.email() != null ? in.email() : current.email(),
        in.role() != null ? in.role() : current.role(),
        in.active() != null ? in.active() : current.active(),
        in.branchIds() != null ? in.branchIds() : current.branchIds(),
        in.allBranches() != null ? in.allBranches() : current.allBranches(),
        current.createdAt()));
    scope(s, Rights.of(current), Rights.of(next));
    List<User> users = dao.list();
    badRequest(first(
        UserRules.userIdentityError(users, next.username(), next.email(), next.role(), id),
        UserRules.userBranchError(next.allBranches(), next.branchIds(), branches.list())));
    badRequest(first(
        UserRules.userChangeError(users, id, false, next.role(), next.active(), s.user().id()),
        knownRole(next.role())));
    limits.check(s, LimitKind.USERS, next.active(), current.active());
    dao.update(next);
    User saved = find(id);
    record(s, saved, "แก้");
    return saved;
  }

  @Transactional
  public void delete(ShopSession s, long id) {
    locks.lock(TenantLocks.ACCOUNTS);
    User current = find(id);
    scope(s, Rights.of(current), null);
    badRequest(UserRules.userChangeError(dao.list(), id, true, current.role(), false, s.user().id()));
    tokens.removeFor(s.tenantId(), id);
    dao.delete(id);
    audit.record(s, "user_change", null, "user", id, current.username(), "ลบผู้ใช้");
  }

  /** {@code PUT /users/:id/password}: admin ตั้งรหัสใหม่ให้ (E10). */
  @Transactional
  public void setPassword(ShopSession s, long id, String password) {
    User user = find(id);
    scope(s, Rights.of(user), Rights.of(user));
    badRequest(UserRules.passwordError(password));
    dao.setPassword(id, passwords.encode(password));
    audit.record(s, "user_password", null, "user", id, user.username(), "ผู้ดูแลระบบตั้งรหัสผ่านใหม่ให้");
  }

  /** {@code withUserRules()}: username ตัวเล็ก, อีเมลตัดช่องว่าง, owner ทุกสาขาเสมอ, สาขาไม่ซ้ำเรียงน้อยไปมาก (ว่างเมื่อทุกสาขา). */
  static User withUserRules(User u) {
    boolean all = u.allBranches() || UserRules.isOwner(u.role());
    List<Long> branchIds = all ? List.of() : u.branchIds().stream().distinct().sorted().toList();
    return new User(u.id(), UserRules.normalizeUsername(u.username()), u.name() == null ? "" : u.name(),
        u.email() == null ? "" : u.email().strip(), u.role(), u.active(), branchIds, all,
        u.createdAt() == null ? Instant.EPOCH : u.createdAt());
  }

  private User find(long id) {
    return dao.find(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
  }

  private static void scope(ShopSession s, Rights before, Rights after) {
    String problem = UserRules.userScopeError(s.user(), before, after);
    if (problem != null) {
      throw new ApiException(HttpStatus.FORBIDDEN, problem);
    }
  }

  /** role นอก owner / admin / user (mock ไม่ตรวจ — DB ปฏิเสธ) → 400 ข้อความกลาง. */
  private static String knownRole(String role) {
    return ROLE_LABEL.containsKey(role) ? null : "ข้อมูลที่ส่งมาไม่ถูกต้อง";
  }

  private static String first(String... problems) {
    for (String p : problems) {
      if (p != null) {
        return p;
      }
    }
    return null;
  }

  private static void badRequest(String problem) {
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
  }

  private void record(ShopSession s, User u, String verb) {
    String rights = ROLE_LABEL.get(u.role()) + " · "
        + (u.allBranches() ? "ทุกสาขา" : "สาขา " + AuditRules.auditBranchCodes(u.branchIds(), branches.list()))
        + (u.active() ? "" : " · ปิดใช้งาน");
    audit.record(s, "user_change", null, "user", u.id(), u.username(), verb + "ผู้ใช้ — " + rights);
  }
}
