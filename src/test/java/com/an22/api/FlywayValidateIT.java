package com.an22.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** ทางที่ 2: FLYWAY_MIGRATE=false → validate ผ่าน PgBouncer ด้วย an22_app — ไม่ต้องมีรหัสของ owner. */
@TestPropertySource(properties = {"an22.db.migrate=false", "an22.db.owner-password="})
class FlywayValidateIT extends ApiTest {

  @Test
  void startsWithValidateOnly() throws Exception {
    mvc.perform(get("/readyz")).andExpect(status().isOk());
  }
}
