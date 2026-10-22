package com.an22.api.support;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL + PgBouncer ชุดเดียวต่อ JVM (singleton) — เหมือน compose.yaml ทุกอย่าง (initdb.sh, roles.sql, pgbouncer.ini):
 *
 * <ul>
 *   <li>roles.sql รันด้วย {@code azure_admin} (ไม่ใช่ superuser — จำลอง server admin ของ Azure Flexible Server)
 *   <li>แอปต่อผ่าน PgBouncer (transaction pooling), Flyway migrate ตรงเข้า postgres
 *   <li>รหัส / กุญแจสุ่มใหม่ทุกรอบ
 * </ul>
 */
public final class TestDatabase {

  public static final String DB_NAME = "an22";
  public static final String ADMIN_USER = "azure_admin";
  public static final String ADMIN_PASSWORD = secret();
  public static final String OWNER_PASSWORD = secret();
  public static final String APP_PASSWORD = secret();
  public static final String PLATFORM_PASSWORD = secret();
  public static final String PII_KEYS = "1:" + key();
  public static final String PII_HASH_KEY = key();
  public static final String JWT_SECRET = "1:" + key();

  private static final Network NETWORK = Network.newNetwork();

  public static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.6")
          .withNetwork(NETWORK)
          .withNetworkAliases("db")
          .withEnv("DB_ADMIN_USER", ADMIN_USER)
          .withEnv("DB_ADMIN_PASSWORD", ADMIN_PASSWORD)
          .withEnv("DB_NAME", DB_NAME)
          .withEnv("DB_OWNER_PASSWORD", OWNER_PASSWORD)
          .withEnv("DB_APP_PASSWORD", APP_PASSWORD)
          .withEnv("DB_PLATFORM_PASSWORD", PLATFORM_PASSWORD)
          .withCopyFileToContainer(MountableFile.forHostPath("db/roles.sql"), "/db/roles.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath("docker/initdb.sh", 0755), "/docker-entrypoint-initdb.d/10-an22.sh");

  public static final GenericContainer<?> PGBOUNCER =
      new GenericContainer<>("edoburu/pgbouncer:v1.25.2-p0")
          .withNetwork(NETWORK)
          .dependsOn(POSTGRES)
          .withEnv("DB_APP_PASSWORD", APP_PASSWORD)
          .withEnv("DB_PLATFORM_PASSWORD", PLATFORM_PASSWORD)
          .withCopyFileToContainer(
              MountableFile.forHostPath("docker/pgbouncer/pgbouncer.ini"), "/an22/pgbouncer.ini")
          .withCopyFileToContainer(MountableFile.forHostPath("docker/pgbouncer/start.sh"), "/an22/start.sh")
          .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "/an22/start.sh"))
          .withExposedPorts(6432)
          .waitingFor(Wait.forListeningPort());

  private static boolean started;

  private TestDatabase() {}

  /**
   * เปิด container ครั้งแรกที่เรียก — ไม่ทำใน static initializer: lambda ของ class นี้ (entrypoint ของ pgbouncer) ถูกเรียกจาก
   * thread อื่นระหว่าง start → deadlock รอ class init.
   */
  public static synchronized void start() {
    if (!started) {
      Startables.deepStart(POSTGRES, PGBOUNCER).join();
      started = true;
    }
  }

  /** ผ่าน PgBouncer (DB_URL ของแอป). */
  public static String poolerUrl() {
    start();
    return "jdbc:postgresql://" + PGBOUNCER.getHost() + ":" + PGBOUNCER.getMappedPort(6432) + "/" + DB_NAME;
  }

  /** ตรงเข้า PostgreSQL (DB_MIGRATE_URL). */
  public static String directUrl() {
    start();
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DB_NAME;
  }

  /** ค่า env ของแอปใน test (ชื่อ property แทน env). */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("an22.db.url", TestDatabase::poolerUrl);
    registry.add("an22.db.migrate-url", TestDatabase::directUrl);
    registry.add("an22.db.app-password", () -> APP_PASSWORD);
    registry.add("an22.db.platform-password", () -> PLATFORM_PASSWORD);
    registry.add("an22.db.owner-password", () -> OWNER_PASSWORD);
    registry.add("an22.pii.keys", () -> PII_KEYS);
    registry.add("an22.pii.hash-key", () -> PII_HASH_KEY);
    registry.add("an22.jwt.secret", () -> JWT_SECRET);
  }

  /** connection superuser ของ container ตรงเข้า database ของแอป — ใช้ตรวจ role / schema ใน test เท่านั้น. */
  public static Connection superuser() throws SQLException {
    return DriverManager.getConnection(directUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String secret() {
    byte[] bytes = new byte[18];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String key() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
