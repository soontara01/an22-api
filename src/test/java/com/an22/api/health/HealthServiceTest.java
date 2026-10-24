package com.an22.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.an22.api.web.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** E2E B1 — ต่อ DB ไม่ได้ตอนเปิด transaction ต้องเป็น 503 (ไม่ใช่ 500). */
class HealthServiceTest {

  @Test
  void unreachableDatabaseIs503() {
    // port 1 ไม่มีอะไรฟัง → connection refused ทันที
    DriverManagerDataSource unreachable = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/an22?connectTimeout=2", "x", "x");
    HealthService service = new HealthService(new HealthDao(JdbcClient.create(unreachable)), new ShutdownDrain(Duration.ZERO),
        new DataSourceTransactionManager(unreachable));
    long started = System.nanoTime();
    assertThatThrownBy(service::checkDatabase)
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.getMessage()).isEqualTo(HealthService.NOT_READY);
        });
    assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
  }

  /** E2E B7 — ระหว่าง drain {@code /readyz} 503 โดยไม่แตะ DB; drain หยุดก่อน graceful shutdown ของ web server. */
  @Test
  void drainingIs503BeforeGracefulShutdown() {
    ShutdownDrain drain = new ShutdownDrain(Duration.ofMillis(300));
    DriverManagerDataSource unused = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/an22", "x", "x");
    HealthService service = new HealthService(new HealthDao(JdbcClient.create(unused)), drain, new DataSourceTransactionManager(unused));
    drain.start();
    assertThat(drain.draining()).isFalse();
    long started = System.nanoTime();
    drain.stop();
    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(300_000_000L);
    assertThat(drain.isRunning()).isFalse();
    assertThatThrownBy(service::checkDatabase)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(drain.getPhase()).isGreaterThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
  }
}
