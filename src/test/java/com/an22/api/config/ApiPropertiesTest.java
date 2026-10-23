package com.an22.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiPropertiesTest {

  private static ApiProperties props(String url, String migrateUrl, String ownerPassword, boolean migrate) {
    return new ApiProperties(
        new ApiProperties.Db(
            url, migrateUrl, "an22_app", "app-secret", "an22_platform", "platform-secret", "an22_owner",
            ownerPassword, 10, migrate, List.of("filesystem:db/migration")),
        new ApiProperties.Pii("k", "h"),
        new ApiProperties.Jwt("1:secret", 7),
        new ApiProperties.Log(ApiProperties.LogBody.ERROR, 4096),
        new ApiProperties.App("https://shop.example.test", true));
  }

  @Test
  void validConfigPasses() {
    assertThatCode(
            () -> props("jdbc:postgresql://db:6432/an22", "jdbc:postgresql://db:5432/an22", "o", true).check())
        .doesNotThrowAnyException();
  }

  @Test
  void appUrlMustNotBeTheDirectServer() {
    assertThatThrownBy(
            () -> props("jdbc:postgresql://DB/an22?ssl=true", "jdbc:postgresql://db:5432/an22", "o", true).check())
        .hasMessageContaining("DB_URL ต้องชี้ PgBouncer");
  }

  @Test
  void ownerPasswordOnlyNeededWhenMigrating() {
    assertThatThrownBy(
            () -> props("jdbc:postgresql://db:6432/an22", "jdbc:postgresql://db:5432/an22", "", true).check())
        .hasMessageContaining("ต้องตั้ง DB_OWNER_PASSWORD");
    assertThatCode(
            () -> props("jdbc:postgresql://db:6432/an22", "jdbc:postgresql://db:5432/an22", "", false).check())
        .doesNotThrowAnyException();
  }

  @Test
  void messageNamesVariablesNotValues() {
    assertThatThrownBy(() -> props("", "", "", true).check())
        .hasMessageContaining("ต้องตั้ง DB_URL")
        .hasMessageContaining("ต้องตั้ง DB_MIGRATE_URL")
        .hasMessageNotContaining("app-secret")
        .hasMessageNotContaining("platform-secret");
  }

  @Test
  void hostPortDefaultsTo5432() {
    assertThat(ApiProperties.hostPort("jdbc:postgresql://x.postgres.database.azure.com/an22?sslmode=verify-full"))
        .isEqualTo("x.postgres.database.azure.com:5432");
  }
}
