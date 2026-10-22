package com.an22.api;

import com.an22.api.config.ClockConfig;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    // เวลาท้องถิ่น Asia/Bangkok ทุกที่ (ไม่พึ่ง TZ ของเครื่อง / container) — log และ TimeZone ของ session DB ตามนี้;
    // กฎ / วันธุรกิจยังใช้ Clock (ClockConfig) เสมอ, JSON เวลาเป็น Instant (…Z)
    TimeZone.setDefault(TimeZone.getTimeZone(ClockConfig.BANGKOK));
    SpringApplication.run(Application.class, args);
  }
}
