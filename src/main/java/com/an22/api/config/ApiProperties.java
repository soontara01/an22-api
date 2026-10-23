package com.an22.api.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ค่าจาก env (ผูกใน application.yml) — ตรวจด้วย {@link #check()} ไม่ใช่ bean validation
 * เพราะ error ของการ bind พิมพ์ค่าที่ตั้งไว้ลง log (มีรหัสผ่าน / กุญแจ).
 */
@ConfigurationProperties("an22")
public record ApiProperties(Db db, Pii pii, Jwt jwt, Log log, App app) {

  public record Db(
      String url,
      String migrateUrl,
      String appUser,
      String appPassword,
      String platformUser,
      String platformPassword,
      String ownerUser,
      String ownerPassword,
      @DefaultValue("10") int poolSize,
      @DefaultValue("true") boolean migrate,
      @DefaultValue("filesystem:db/migration") List<String> locations) {}

  public record Pii(String keys, String hashKey) {}

  /**
   * @param secret {@code JWT_SECRET} = {@code <kid>:<base64 ≥ 32 byte>,…} — เซ็นด้วย kid สูงสุด ตรวจได้ทุกตัว (หมุน secret)
   * @param ttlDays {@code JWT_TTL_DAYS} อายุ token (≥ 7 = เพดาน session ของ web, E48)
   */
  public record Jwt(String secret, @DefaultValue("7") int ttlDays) {}

  public enum LogBody {
    OFF,
    ERROR,
    ALL
  }

  public record Log(@DefaultValue("error") LogBody body, @DefaultValue("4096") int bodyMax) {}

  /**
   * @param baseUrl {@code APP_BASE_URL} = URL ของหน้าเว็บ (an22-web) สำหรับลิงก์ในอีเมล — ไม่ใช้ header {@code Host} (E49)
   * @param jobs {@code JOBS_ENABLED} เปิด job ตามเวลา (ออกใบแจ้งหนี้ / ระงับค้างชำระ / ล้างของหมดอายุ) — ทุก instance เปิดได้ (ล็อกใน DB)
   */
  public record App(String baseUrl, @DefaultValue("true") boolean jobs) {}

  /** ตรวจค่าบังคับ — ข้อความบอกชื่อตัวแปร ไม่บอกค่า. */
  public void check() {
    List<String> problems = new ArrayList<>();
    required(problems, "DB_URL", db.url());
    required(problems, "DB_MIGRATE_URL", db.migrateUrl());
    required(problems, "DB_APP_USER", db.appUser());
    required(problems, "DB_APP_PASSWORD", db.appPassword());
    required(problems, "DB_PLATFORM_USER", db.platformUser());
    required(problems, "DB_PLATFORM_PASSWORD", db.platformPassword());
    if (db.migrate()) {
      required(problems, "DB_OWNER_USER", db.ownerUser());
      required(problems, "DB_OWNER_PASSWORD", db.ownerPassword());
    }
    required(problems, "JWT_SECRET", jwt.secret());
    if (app == null || isBlank(app.baseUrl()) || !app.baseUrl().matches("https?://[^\\s/]+(/\\S*)?")) {
      problems.add("ต้องตั้ง APP_BASE_URL เป็น URL http(s) ของหน้าเว็บ");
    }
    if (jwt.ttlDays() < 7) {
      problems.add("JWT_TTL_DAYS ต้อง ≥ 7 (เพดาน session ของ an22-web)");
    }
    if (db.poolSize() < 1) {
      problems.add("DB_POOL_SIZE ต้องเป็นจำนวนเต็ม ≥ 1");
    }
    if (log.bodyMax() < 256) {
      problems.add("LOG_BODY_MAX ต้อง ≥ 256");
    }
    if (!isBlank(db.url())
        && !isBlank(db.migrateUrl())
        && hostPort(db.url()).equals(hostPort(db.migrateUrl()))) {
      problems.add("DB_URL ต้องชี้ PgBouncer ไม่ใช่ host:port เดียวกับ DB_MIGRATE_URL (PostgreSQL ตรง)");
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("ตั้งค่าไม่ถูกต้อง: " + String.join("; ", problems));
    }
  }

  private static void required(List<String> problems, String name, String value) {
    if (isBlank(value)) {
      problems.add("ต้องตั้ง " + name);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** {@code jdbc:postgresql://host:port/db?…} → {@code host:port} (ไม่มี port = 5432). */
  static String hostPort(String jdbcUrl) {
    String rest = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
    int end = rest.indexOf('/');
    String hostPort = (end < 0 ? rest : rest.substring(0, end)).toLowerCase();
    return hostPort.contains(":") ? hostPort : hostPort + ":5432";
  }
}
