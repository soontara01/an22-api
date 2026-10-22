package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.Branch;
import com.an22.api.model.User;
import com.an22.api.rules.UserRules.Rights;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code user.model.spec.ts} (userChangeError, identity, branches, scope, password, owner, branch scope). */
class UserChangeRulesTest {

  private static User u(long id, String role, boolean active) {
    return new User(id, "u" + id, "U" + id, "u" + id + "@x.test", role, active, List.of(), false, null);
  }

  private static User named(long id, String username, String email) {
    return new User(id, username, username, email, "user", true, List.of(), false, null);
  }

  private static User admin(boolean all, Long... branchIds) {
    return new User(99, "a", "a", "", "admin", true, List.of(branchIds), all, null);
  }

  private static Rights r(boolean all, Long... ids) {
    return new Rights(all, List.of(ids));
  }

  private final List<User> users = List.of(u(1, "admin", true), u(2, "user", true), u(3, "admin", false));

  @Test
  void blocksDemotingDeactivatingOrDeletingYourself() {
    List<User> withSecond = List.of(u(1, "admin", true), u(2, "user", true), u(3, "admin", false), u(4, "admin", true));
    assertThat(UserRules.userChangeError(withSecond, 1, false, "user", true, 1L)).isEqualTo("ลดสิทธิ์ผู้ดูแลระบบของตัวเองไม่ได้");
    assertThat(UserRules.userChangeError(withSecond, 1, false, "admin", false, 1L)).isEqualTo("ปิดใช้งานบัญชีของตัวเองไม่ได้");
    assertThat(UserRules.userChangeError(withSecond, 1, false, "admin", true, 1L)).isNull();
    assertThat(UserRules.userChangeError(users, 1, true, null, false, 1L)).isEqualTo("ลบบัญชีของตัวเองไม่ได้");
  }

  @Test
  void keepsTheLastActiveAdmin() {
    String last = "ต้องมีผู้ดูแลระบบที่ใช้งานอยู่อย่างน้อย 1 คน";
    assertThat(UserRules.userChangeError(users, 1, true, null, false, 9L)).isEqualTo(last);
    assertThat(UserRules.userChangeError(users, 1, false, "user", true, 9L)).isEqualTo(last);
    List<User> withSecond = List.of(u(1, "admin", true), u(2, "user", true), u(3, "admin", false), u(4, "admin", true));
    assertThat(UserRules.userChangeError(withSecond, 1, true, null, false, 4L)).isNull();
  }

  @Test
  void allowsOrdinaryChanges() {
    assertThat(UserRules.userChangeError(users, 2, true, null, false, 1L)).isNull();
    assertThat(UserRules.userChangeError(users, 3, true, null, false, 1L)).isNull();
    assertThat(UserRules.userChangeError(users, 2, false, "admin", true, 1L)).isNull();
  }

  @Test
  void checksUsernameAndEmail() {
    assertThat(UserRules.usernameError("")).isEqualTo("กรุณากรอกชื่อผู้ใช้");
    assertThat(UserRules.usernameError("ab")).isEqualTo("ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว");
    assertThat(UserRules.usernameError("สมชาย")).isEqualTo("ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว");
    assertThat(UserRules.usernameError(" Som.Chai_2-x ")).isNull();
    List<User> list = List.of(named(1, "admin", "admin@example.com"), named(2, "somchai", ""));
    assertThat(UserRules.userIdentityError(list, "SOMCHAI", "", "user", null)).isEqualTo("ชื่อผู้ใช้นี้มีอยู่แล้ว");
    assertThat(UserRules.userIdentityError(list, "somchai", "", "user", 2L)).isNull();
    assertThat(UserRules.userIdentityError(list, "somying", "", "user", null)).isNull();
    assertThat(UserRules.userIdentityError(list, "somying", "x@", "user", null)).isEqualTo("รูปแบบอีเมลไม่ถูกต้อง");
    assertThat(UserRules.userIdentityError(list, "somying", " Admin@Example.com", "user", null)).isEqualTo("อีเมลนี้มีผู้ใช้อื่นใช้แล้ว");
    assertThat(UserRules.userIdentityError(list, "admin", "admin@example.com", "user", 1L)).isNull();
    assertThat(UserRules.userIdentityError(List.of(), "boss", "", "owner", null))
        .isEqualTo("เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)");
    assertThat(UserRules.userIdentityError(List.of(), "boss", "", "admin", null)).isNull();
  }

  @Test
  void needsAllBranchesOrAtLeastOneActiveBranch() {
    List<Branch> branches = List.of(
        new Branch(1, "A", "a", "00000", "x", "", "", true), new Branch(2, "B", "b", "00001", "x", "", "", true),
        new Branch(3, "C", "c", "00002", "x", "", "", false));
    assertThat(UserRules.userBranchError(true, List.of(), branches)).isNull();
    assertThat(UserRules.userBranchError(false, List.of(), branches)).isEqualTo("กรุณาเลือกสาขาอย่างน้อย 1 สาขา");
    assertThat(UserRules.userBranchError(false, List.of(1L, 3L), branches)).isEqualTo("สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว");
    assertThat(UserRules.userBranchError(false, List.of(2L), branches)).isNull();
  }

  @Test
  void keepsABranchAdminToUsersOfTheirOwnBranches() {
    User branchAdmin = admin(false, 1L, 2L);
    assertThat(UserRules.userScopeError(admin(true), r(true), null)).isNull();
    assertThat(UserRules.userScopeError(branchAdmin, null, r(false, 2L))).isNull();
    assertThat(UserRules.userScopeError(branchAdmin, r(false, 1L), r(false, 1L, 2L))).isNull();
    assertThat(UserRules.userScopeError(branchAdmin, r(false, 3L), r(false, 1L)))
        .isEqualTo("จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท");
    assertThat(UserRules.userScopeError(branchAdmin, r(true), null))
        .isEqualTo("จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท");
    assertThat(UserRules.userScopeError(branchAdmin, null, r(true)))
        .isEqualTo("ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท");
    assertThat(UserRules.userScopeError(branchAdmin, r(false, 1L), r(false, 1L, 3L)))
        .isEqualTo("ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท");
  }

  @Test
  void checksAPassword() {
    assertThat(UserRules.passwordError("")).isEqualTo("กรุณากรอกรหัสผ่าน");
    assertThat(UserRules.passwordError(" 1234")).isEqualTo("รหัสผ่านต้องไม่ขึ้นต้นหรือลงท้ายด้วยช่องว่าง");
    assertThat(UserRules.passwordError("123")).isEqualTo("รหัสผ่านต้องมีอย่างน้อย 4 ตัว");
    assertThat(UserRules.passwordError("x".repeat(65))).isEqualTo("รหัสผ่านต้องไม่เกิน 64 ตัว");
    assertThat(UserRules.passwordError("1234")).isNull();
  }

  @Test
  void branchScopeOfLists() {
    List<Branch> branches = List.of(
        new Branch(1, "A", "a", "00000", "x", "", "", true), new Branch(2, "B", "b", "00001", "x", "", "", true),
        new Branch(3, "C", "c", "00002", "x", "", "", false));
    User companyAdmin = new User(1, "a", "a", "", "admin", true, List.of(), true, null);
    User branchAdmin = new User(2, "b", "b", "", "admin", true, List.of(2L), false, null);
    User staff = new User(3, "c", "c", "", "user", true, List.of(1L), false, null);
    assertThat(UserRules.parseBranchScope(null, 2)).isEqualTo(2L);
    assertThat(UserRules.parseBranchScope("all", 2)).isEqualTo("all");
    assertThat(UserRules.parseBranchScope("1", 2)).isEqualTo(1L);
    assertThat(UserRules.parseBranchScope("x", 2)).isNull();
    assertThat(UserRules.branchScopeError("all", companyAdmin, branches)).isNull();
    assertThat(UserRules.branchScopeError(3L, companyAdmin, branches)).isNull();
    assertThat(UserRules.branchScopeError("all", branchAdmin, branches)).isEqualTo("ดูทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท");
    assertThat(UserRules.branchScopeError(2L, branchAdmin, branches)).isNull();
    assertThat(UserRules.branchScopeError(1L, branchAdmin, branches)).isEqualTo("ไม่มีสิทธิ์ดูข้อมูลของสาขานี้");
    assertThat(UserRules.branchScopeError(1L, staff, branches)).isNull();
    assertThat(UserRules.branchScopeError(9L, companyAdmin, branches)).isEqualTo("ไม่มีสิทธิ์ดูข้อมูลของสาขานี้");
  }

  @Test
  void ownerRules() {
    List<User> list = List.of(u(1, "owner", true), u(2, "admin", true), u(3, "user", true));
    assertThat(UserRules.userChangeError(list, 1, true, null, false, 2L)).isEqualTo("ลบเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน");
    assertThat(UserRules.userChangeError(list, 1, false, "owner", false, 2L))
        .isEqualTo("ปิดใช้งานเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน");
    assertThat(UserRules.userChangeError(list, 1, false, "admin", true, 2L))
        .isEqualTo("เปลี่ยนสิทธิ์ของเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน");
    assertThat(UserRules.userChangeError(list, 3, false, "owner", true, 2L)).isEqualTo("ตั้งเจ้าของบริษัทได้ด้วยการโอนความเป็นเจ้าของเท่านั้น");
    assertThat(UserRules.userChangeError(list, 1, false, "owner", true, 2L)).isNull();
    assertThat(UserRules.userChangeError(list, 2, false, "user", true, 2L)).isEqualTo("ลดสิทธิ์ผู้ดูแลระบบของตัวเองไม่ได้");
    assertThat(UserRules.userChangeError(list, 2, false, "user", true, 1L)).isNull();
    assertThat(UserRules.ownerRoleError(null, "owner")).isNotNull();
    assertThat(UserRules.ownerRoleError(null, "admin")).isNull();

    List<User> transfer = List.of(u(1, "owner", true), u(2, "admin", true), u(3, "user", false),
        new User(4, "u4", "U4", "", "user", true, List.of(), false, null));
    User owner = transfer.getFirst();
    assertThat(UserRules.ownerTransferError(transfer, transfer.get(1), 1)).isEqualTo("โอนความเป็นเจ้าของได้เฉพาะเจ้าของบริษัท");
    assertThat(UserRules.ownerTransferError(transfer, owner, 1)).isEqualTo("เลือกผู้ใช้อื่นที่จะรับโอน");
    assertThat(UserRules.ownerTransferError(transfer, owner, 3)).isEqualTo("ผู้รับโอนต้องเป็นผู้ใช้ที่ใช้งานอยู่");
    assertThat(UserRules.ownerTransferError(transfer, owner, 4)).isEqualTo("ผู้รับโอนต้องมีอีเมล — แก้ข้อมูลผู้ใช้ให้มีอีเมลก่อน");
    assertThat(UserRules.ownerTransferError(transfer, owner, 9)).isEqualTo("ไม่พบผู้ใช้ที่จะรับโอน");
    assertThat(UserRules.ownerTransferError(transfer, owner, 2)).isNull();
  }
}
