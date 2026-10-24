package com.an22.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.an22.api.web.ApiException;
import org.junit.jupiter.api.Test;
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
    HealthService service = new HealthService(new HealthDao(JdbcClient.create(unreachable)), new DataSourceTransactionManager(unreachable));
    long started = System.nanoTime();
    assertThatThrownBy(service::checkDatabase)
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.getMessage()).isEqualTo(HealthService.NOT_READY);
        });
    assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
  }
}
