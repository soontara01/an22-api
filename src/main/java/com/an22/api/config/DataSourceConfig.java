package com.an22.api.config;

import com.an22.api.db.TenantTransactionManager;
import com.an22.api.db.TxGuardDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * DataSource 2 ตัว ต่อผ่าน PgBouncer ({@code DB_URL}) ทั้งคู่:
 *
 * <ul>
 *   <li>{@code app} — role {@code an22_app} (ใต้ RLS) — tx manager {@code appTx} เป็นค่าเริ่ม
 *   <li>{@code platform} — role {@code an22_platform} (BYPASSRLS) — ใช้ {@code @Transactional("platformTx")}
 * </ul>
 *
 * JdbcClient ห่อด้วย {@link TxGuardDataSource} (query นอก transaction → error); Flyway ไม่ใช้ตัวนี้ (ต่อ PostgreSQL ตรง —
 * {@link FlywayConfig}).
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

  @Bean
  @Primary
  DataSource appDataSource(ApiProperties props) {
    props.check();
    ApiProperties.Db db = props.db();
    return pool("app", db.url(), db.appUser(), db.appPassword(), db.poolSize());
  }

  @Bean
  DataSource platformDataSource(ApiProperties props) {
    ApiProperties.Db db = props.db();
    return pool("platform", db.url(), db.platformUser(), db.platformPassword(), db.poolSize());
  }

  @Bean
  @Primary
  PlatformTransactionManager appTx(@Qualifier("appDataSource") DataSource dataSource) {
    return txManager(dataSource);
  }

  @Bean
  PlatformTransactionManager platformTx(@Qualifier("platformDataSource") DataSource dataSource) {
    return txManager(dataSource);
  }

  @Bean
  @Primary
  @DependsOn("flywayRunner")
  JdbcClient appJdbc(@Qualifier("appDataSource") DataSource dataSource) {
    return JdbcClient.create(new TxGuardDataSource(dataSource));
  }

  @Bean
  @DependsOn("flywayRunner")
  JdbcClient platformJdbc(@Qualifier("platformDataSource") DataSource dataSource) {
    return JdbcClient.create(new TxGuardDataSource(dataSource));
  }

  /** ตั้ง app.* จาก TxActor ทุก transaction + readOnly → SET TRANSACTION READ ONLY (ชั้นที่สองของ "GET ห้ามแก้ข้อมูล", E49). */
  private static TenantTransactionManager txManager(DataSource dataSource) {
    return new TenantTransactionManager(dataSource);
  }

  private static HikariDataSource pool(String name, String url, String user, String password, int size) {
    HikariConfig config = new HikariConfig();
    config.setPoolName(name);
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(size);
    config.setMinimumIdle(Math.min(2, size));
    // ไม่ต่อ DB ตอนสร้าง pool — start ได้แม้ DB ยังไม่พร้อม (readyz บอก load balancer)
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }
}
