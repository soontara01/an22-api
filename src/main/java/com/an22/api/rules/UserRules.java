package com.an22.api.rules;

import com.an22.api.model.Branch;
import com.an22.api.model.User;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** port จาก {@code core/models/user.model.ts} — ข้อความไทยตรงทุกตัวอักษร. */
public final class UserRules {

  private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
  static final String OWNER_EMAIL_REQUIRED = "เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)";

  private UserRules() {}

  /** สิทธิ์สาขาของผู้ใช้ (ก่อน / หลังแก้) — ใช้กับ {@link #userScopeError}. */
  public record Rights(boolean allBranches, List<Long> branchIds) {
    public static Rights of(User u) {
      return new Rights(u.allBranches(), u.branchIds());
    }
  }

  /** สิทธิ์ admin ทุกอย่าง: admin หรือ owner (E33). */
  public static boolean hasAdminRights(String role) {
    return "admin".equals(role) || "owner".equals(role);
  }

  public static boolean isOwner(String role) {
    return "owner".equals(role);
  }

  /** งานระดับบริษัท (E5 / E6): owner หรือ admin ทุกสาขา. */
  public static boolean isCompanyAdmin(String role, boolean allBranches) {
    return isOwner(role) || ("admin".equals(role) && allBranches);
  }

  public static boolean isCompanyAdmin(User u) {
    return isCompanyAdmin(u.role(), u.allBranches());
  }

  /** ทำงานในสาขานี้ได้ไหม — สาขาที่ปิดใช้งานไม่เปิดให้ใคร. */
  public static boolean canWorkIn(User user, Branch branch) {
    return branch.active() && (user.allBranches() || user.branchIds().contains(branch.id()));
  }

  /** {@code normalizeUsername()}: ตัดช่องว่างหัวท้าย + ตัวเล็ก. */
  public static String normalizeUsername(String username) {
    return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
  }

  /** {@code parseBranchScope()}: {@code ?branchId=} ของรายการ — ไม่ส่ง = สาขาที่ทำงาน, {@code all}, อ่านไม่ได้ = null (400). */
  public static Object parseBranchScope(String param, long workingBranchId) {
    if (param == null || param.isEmpty()) {
      return workingBranchId;
    }
    if ("all".equals(param)) {
      return "all";
    }
    try {
      long id = Long.parseLong(param);
      return id > 0 ? id : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** {@code branchScopeError()} (E23): admin บริษัทดูได้ทุกสาขา / {@code all}, คนอื่นเฉพาะสาขาที่ทำงานได้ → 403. */
  public static String branchScopeError(Object scope, User user, List<Branch> branches) {
    if ("all".equals(scope)) {
      return isCompanyAdmin(user) ? null : "ดูทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท";
    }
    Branch branch = branches.stream().filter(b -> Objects.equals(b.id(), scope)).findFirst().orElse(null);
    if (branch != null && (isCompanyAdmin(user) || canWorkIn(user, branch))) {
      return null;
    }
    return "ไม่มีสิทธิ์ดูข้อมูลของสาขานี้";
  }

  /** {@code usernameError()}. */
  public static String usernameError(String username) {
    String name = normalizeUsername(username);
    if (name.isEmpty()) {
      return "กรุณากรอกชื่อผู้ใช้";
    }
    if (!USERNAME.matcher(name).matches()) {
      return "ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว";
    }
    return null;
  }

  /**
   * {@code userIdentityError()} (B5): username ถูกรูปแบบและไม่ซ้ำในบริษัท (ไม่สนตัวพิมพ์); อีเมลไม่บังคับ (owner บังคับ) แต่ถ้ามีต้องถูก
   * รูปแบบและไม่ซ้ำ.
   *
   * @param exceptId ผู้ใช้ที่กำลังแก้ (null = ใหม่)
   */
  public static String userIdentityError(List<User> users, String username, String email, String role, Long exceptId) {
    String problem = usernameError(username);
    if (problem != null) {
      return problem;
    }
    List<User> others = users.stream().filter(u -> exceptId == null || u.id() != exceptId).toList();
    String name = normalizeUsername(username);
    if (others.stream().anyMatch(u -> normalizeUsername(u.username()).equals(name))) {
      return "ชื่อผู้ใช้นี้มีอยู่แล้ว";
    }
    String mail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    if (mail.isEmpty()) {
      return isOwner(role) ? OWNER_EMAIL_REQUIRED : null;
    }
    if (!EMAIL.matcher(mail).matches()) {
      return "รูปแบบอีเมลไม่ถูกต้อง";
    }
    if (others.stream().anyMatch(u -> u.email().strip().toLowerCase(Locale.ROOT).equals(mail))) {
      return "อีเมลนี้มีผู้ใช้อื่นใช้แล้ว";
    }
    return null;
  }

  /** {@code userBranchError()}: ทุกสาขา หรือสาขาที่ใช้งานอยู่ ≥ 1. */
  public static String userBranchError(boolean allBranches, List<Long> branchIds, List<Branch> branches) {
    if (allBranches) {
      return null;
    }
    if (branchIds.isEmpty()) {
      return "กรุณาเลือกสาขาอย่างน้อย 1 สาขา";
    }
    Set<Long> active = new HashSet<>();
    branches.stream().filter(Branch::active).forEach(b -> active.add(b.id()));
    if (!active.containsAll(branchIds)) {
      return "สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว";
    }
    return null;
  }

  /**
   * {@code userScopeError()} (E6, → 403): admin ที่ไม่ใช่ทุกสาขาจัดการได้เฉพาะผู้ใช้ในสาขาของตัวเองทั้งหมด และให้สิทธิ์ได้แค่สาขาของตัวเอง.
   *
   * @param before null = ผู้ใช้ใหม่
   * @param after null = ลบ
   */
  public static String userScopeError(User actor, Rights before, Rights after) {
    if (isCompanyAdmin(actor)) {
      return null;
    }
    Set<Long> mine = new HashSet<>(actor.branchIds());
    if (before != null && !within(before, mine)) {
      return "จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท";
    }
    if (after != null && !within(after, mine)) {
      return "ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท";
    }
    return null;
  }

  private static boolean within(Rights u, Set<Long> mine) {
    return !u.allBranches() && mine.containsAll(u.branchIds());
  }

  /** {@code passwordError()} (E10): 4–64 ตัว ไม่มีช่องว่างหัวท้าย. */
  public static String passwordError(String password) {
    if (password == null || password.isEmpty()) {
      return "กรุณากรอกรหัสผ่าน";
    }
    if (!password.equals(password.strip())) {
      return "รหัสผ่านต้องไม่ขึ้นต้นหรือลงท้ายด้วยช่องว่าง";
    }
    if (password.length() < 4) {
      return "รหัสผ่านต้องมีอย่างน้อย 4 ตัว";
    }
    if (password.length() > 64) {
      return "รหัสผ่านต้องไม่เกิน 64 ตัว";
    }
    return null;
  }

  /** {@code ownerRoleError()}: owner เปลี่ยนได้ด้วยการโอนเท่านั้น. {@code before} null = ผู้ใช้ใหม่. */
  public static String ownerRoleError(String before, String after) {
    if (isOwner(after) && !isOwner(before)) {
      return "ตั้งเจ้าของบริษัทได้ด้วยการโอนความเป็นเจ้าของเท่านั้น";
    }
    if (isOwner(before) && !isOwner(after)) {
      return "เปลี่ยนสิทธิ์ของเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน";
    }
    return null;
  }

  /** {@code ownerTransferError()} (E33): เฉพาะ owner โอนให้ผู้ใช้อื่นที่ใช้งานอยู่และมีอีเมล. */
  public static String ownerTransferError(List<User> users, User actor, long targetId) {
    if (!isOwner(actor.role())) {
      return "โอนความเป็นเจ้าของได้เฉพาะเจ้าของบริษัท";
    }
    User target = users.stream().filter(u -> u.id() == targetId).findFirst().orElse(null);
    if (target == null) {
      return "ไม่พบผู้ใช้ที่จะรับโอน";
    }
    if (target.id() == actor.id()) {
      return "เลือกผู้ใช้อื่นที่จะรับโอน";
    }
    if (!target.active()) {
      return "ผู้รับโอนต้องเป็นผู้ใช้ที่ใช้งานอยู่";
    }
    if (target.email().isBlank()) {
      return "ผู้รับโอนต้องมีอีเมล — แก้ข้อมูลผู้ใช้ให้มีอีเมลก่อน";
    }
    return null;
  }

  /**
   * {@code userChangeError()}: ห้ามลด / ปิด / ลบตัวเอง, ต้องเหลือ admin ที่ใช้งาน ≥ 1 (owner นับเป็น admin), owner ปิด / ลบ / เปลี่ยน role ไม่ได้.
   *
   * @param newRole / newActive ค่าหลังแก้ (ลบ = {@code delete} true)
   */
  public static String userChangeError(
      List<User> users, long id, boolean delete, String newRole, boolean newActive, Long actorId) {
    User target = users.stream().filter(u -> u.id() == id).findFirst().orElse(null);
    if (target == null) {
      return null;
    }
    boolean removesAdmin = delete || !hasAdminRights(newRole) || !newActive;
    boolean removesAccess = delete || !newActive;
    if (actorId != null && id == actorId) {
      if (delete) {
        return "ลบบัญชีของตัวเองไม่ได้";
      }
      if (hasAdminRights(target.role()) && !hasAdminRights(newRole)) {
        return "ลดสิทธิ์ผู้ดูแลระบบของตัวเองไม่ได้";
      }
      if (removesAccess) {
        return "ปิดใช้งานบัญชีของตัวเองไม่ได้";
      }
    }
    if (isOwner(target.role())) {
      if (delete) {
        return "ลบเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน";
      }
      if (!newActive) {
        return "ปิดใช้งานเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน";
      }
    }
    if (!delete) {
      String role = ownerRoleError(target.role(), newRole);
      if (role != null) {
        return role;
      }
    }
    if (hasAdminRights(target.role()) && target.active() && removesAdmin) {
      boolean others = users.stream().anyMatch(u -> u.id() != target.id() && hasAdminRights(u.role()) && u.active());
      if (!others) {
        return "ต้องมีผู้ดูแลระบบที่ใช้งานอยู่อย่างน้อย 1 คน";
      }
    }
    return null;
  }
}
