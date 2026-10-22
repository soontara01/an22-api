package com.an22.api.db;

/**
 * ผู้ทำของ request / job ปัจจุบัน (ThreadLocal) — {@link TenantTransactionManager} อ่านตอนเปิดทุก transaction แล้วตั้ง
 * {@code app.tenant_id} / {@code app.user_id} / {@code app.actor_kind} (SET LOCAL) ให้ RLS และ trigger {@code set_row_stamp()}.
 *
 * <p>ตั้งโดย filter ของ session (ร้าน / platform) และ clear เมื่อจบ request — ไม่มี = system ไม่มีบริษัท (RLS ไม่เห็นอะไร).
 *
 * @param tenantId บริษัท (null = ไม่มี — งาน platform / ก่อน login)
 * @param userId {@code dt_users.id} (user) หรือ {@code dt_platform_admins.id} (platform); null = system
 * @param kind {@code user} / {@code platform} / {@code system}
 */
public record TxActor(Long tenantId, Long userId, String kind) {

  public static final TxActor SYSTEM = new TxActor(null, null, "system");

  private static final ThreadLocal<TxActor> CURRENT = new ThreadLocal<>();

  public static TxActor user(long tenantId, long userId) {
    return new TxActor(tenantId, userId, "user");
  }

  public static TxActor platform(long adminId) {
    return new TxActor(null, adminId, "platform");
  }

  public static TxActor current() {
    TxActor actor = CURRENT.get();
    return actor == null ? SYSTEM : actor;
  }

  public static void set(TxActor actor) {
    CURRENT.set(actor);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
