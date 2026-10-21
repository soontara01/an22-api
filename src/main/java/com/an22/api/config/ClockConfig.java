package com.an22.api.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** เวลาของกฎ / วันธุรกิจ = Asia/Bangkok (เหมือน {@code todayIso()} ของหน้าบ้าน) — test แทนด้วย Clock ปลอมได้. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  public static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  @Bean
  Clock clock() {
    return Clock.system(BANGKOK);
  }
}
