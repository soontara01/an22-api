package com.an22.api.rules;

import java.util.Arrays;
import java.util.List;

/** port จาก {@code core/models/plan.model.ts} (E34) — แพ็กเกจตายตัวในโค้ด นับเฉพาะ record ที่ใช้งาน. */
public final class PlanRules {

  public enum LimitKind {
    BRANCHES("สาขา", "สาขา"),
    USERS("ผู้ใช้ที่ใช้งาน", "คน"),
    TERMINALS("เครื่อง POS ที่ใช้งาน", "เครื่อง");

    final String noun;
    final String unit;

    LimitKind(String noun, String unit) {
      this.noun = noun;
      this.unit = unit;
    }
  }

  /** @param branches / users / terminals null = ไม่จำกัด */
  public record Plan(String id, String name, int monthlyPrice, Integer branches, Integer users, Integer terminals) {
    Integer limit(LimitKind kind) {
      return switch (kind) {
        case BRANCHES -> branches;
        case USERS -> users;
        case TERMINALS -> terminals;
      };
    }
  }

  public record Usage(int branches, int users, int terminals) {
    int of(LimitKind kind) {
      return switch (kind) {
        case BRANCHES -> branches;
        case USERS -> users;
        case TERMINALS -> terminals;
      };
    }
  }

  public static final List<Plan> PLANS =
      List.of(
          new Plan("starter", "Starter", 590, 1, 5, 2),
          new Plan("standard", "Standard", 1490, 3, 20, 10),
          new Plan("pro", "Pro", 3490, null, null, null));

  private PlanRules() {}

  /** แพ็กเกจของ id — ไม่รู้จัก = เล็กสุด. */
  public static Plan planOf(String id) {
    return PLANS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(PLANS.getFirst());
  }

  /** {@code planLimitError()}: เพิ่มอีก 1 ที่ใช้งานได้ไหม (server → 403). */
  public static String planLimitError(LimitKind kind, Plan plan, Usage usage) {
    Integer limit = plan.limit(kind);
    if (limit == null || usage.of(kind) < limit) {
      return null;
    }
    return "แพ็กเกจ " + plan.name() + " ใช้" + kind.noun + "ได้ " + limit + " " + kind.unit
        + " — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท";
  }

  /** {@code overLimits()}: ชนิดที่เกินแพ็กเกจอยู่แล้ว (หลังลดแพ็กเกจ). */
  public static List<LimitKind> overLimits(Plan plan, Usage usage) {
    return Arrays.stream(LimitKind.values())
        .filter(k -> plan.limit(k) != null && usage.of(k) > plan.limit(k))
        .toList();
  }
}
