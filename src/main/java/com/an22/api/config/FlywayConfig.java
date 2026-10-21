package com.an22.api.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * script DB ที่ {@code db/} (ไฟล์เดียวกับ {@code scripts/db-apply.sh}) — auto-config ของ Boot ปิดไว้ (application.yml)
 *
 * <ul>
 *   <li>{@code FLYWAY_MIGRATE=true} (ทางที่ 1): migrate ด้วย {@code an22_owner} ต่อ PostgreSQL ตรง ({@code DB_MIGRATE_URL})
 *       — Flyway ใช้ advisory lock แบบ session ซึ่งพังใน transaction pooling
 *   <li>{@code FLYWAY_MIGRATE=false} (ทางที่ 2): validate อย่างเดียวด้วย DataSource ของแอป (ผ่าน PgBouncer, อ่าน
 *       {@code flyway_schema_history}) — version ไม่ตรงกับไฟล์ → ไม่ start; แอปไม่ต้องรู้รหัสของ owner
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

  private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

  /** marker ว่า schema พร้อมแล้ว — bean ที่ใช้ DB {@code @DependsOn("flywayRunner")}. */
  public record FlywayRunner() {}

  @Bean
  FlywayRunner flywayRunner(ApiProperties props, @Qualifier("appDataSource") DataSource appDataSource) {
    ApiProperties.Db db = props.db();
    FluentConfiguration config =
        Flyway.configure()
            .locations(db.locations().toArray(String[]::new))
            .failOnMissingLocations(true)
            // SQL ล้วน รันด้วยมือได้ — ไม่แทนค่า ${…}
            .placeholderReplacement(false)
            .cleanDisabled(true);
    if (db.migrate()) {
      var result =
          config.dataSource(db.migrateUrl(), db.ownerUser(), db.ownerPassword()).load().migrate();
      log.info("flyway migrate: {} migration, version {}", result.migrationsExecuted, result.targetSchemaVersion);
    } else {
      config.dataSource(appDataSource).load().validate();
      log.info("flyway validate: ผ่าน (FLYWAY_MIGRATE=false)");
    }
    return new FlywayRunner();
  }
}
