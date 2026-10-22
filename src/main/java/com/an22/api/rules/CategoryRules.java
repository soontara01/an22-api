package com.an22.api.rules;

import com.an22.api.model.Category;
import com.an22.api.model.CategoryPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** port จาก {@code core/models/category.model.ts} + กฎของ {@code handleCategories()} ใน mock. */
public final class CategoryRules {

  public static final int CATEGORY_MAX_LEVEL = 3;

  private CategoryRules() {}

  /** {@code categoryPath()}: "ไอที > คอมพิวเตอร์ > โน้ตบุ๊ก" ('' เมื่อไม่รู้จัก). */
  public static String categoryPath(List<Category> list, Long id) {
    Map<Long, Category> byId = list.stream().collect(Collectors.toMap(Category::id, Function.identity()));
    Deque<String> names = new ArrayDeque<>();
    Category current = id == null ? null : byId.get(id);
    while (current != null) {
      names.addFirst(current.name());
      current = current.parentId() == null ? null : byId.get(current.parentId());
    }
    return String.join(" > ", names);
  }

  /** {@code isLeaf()}: ไม่มีหมวดใดมีหมวดนี้เป็นแม่. */
  public static boolean isLeaf(List<Category> list, long id) {
    return list.stream().noneMatch(c -> c.parentId() != null && c.parentId() == id);
  }

  /** {@code validate()} ของ mock: รหัส A-Z 0-9 - ไม่ซ้ำ, ชื่อไม่ว่าง. {@code exceptId} = หมวดที่แก้. */
  public static String categoryError(CategoryPayload p, List<Category> categories, Long exceptId) {
    if (p.code() == null || !p.code().matches("[A-Z0-9-]+")) {
      return "รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -";
    }
    if (p.name() == null || p.name().isBlank()) {
      return "กรุณากรอกชื่อหมวดหมู่";
    }
    if (categories.stream().anyMatch(c -> (exceptId == null || c.id() != exceptId) && c.code().equals(p.code()))) {
      return "รหัส " + p.code() + " ซ้ำ";
    }
    return null;
  }

  /** แม่ของหมวดใหม่: ต้องมีจริง, ไม่เกิน 3 ระดับ, แม่ที่มี SKU แตกหมวดย่อยไม่ได้. */
  public static String parentError(Long parentId, Category parent) {
    if (parentId != null && parent == null) {
      return "ไม่พบหมวดหมู่แม่";
    }
    if (parent != null && parent.level() >= CATEGORY_MAX_LEVEL) {
      return "แบ่งหมวดหมู่ได้ไม่เกิน " + CATEGORY_MAX_LEVEL + " ระดับ";
    }
    if (parent != null && parent.productCount() > 0) {
      return parent.name() + " มี SKU อยู่ " + parent.productCount() + " รายการ แบ่งหมวดย่อยไม่ได้";
    }
    return null;
  }
}
