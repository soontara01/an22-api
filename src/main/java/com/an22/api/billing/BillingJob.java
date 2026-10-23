package com.an22.api.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ตั้งเวลา job รายวัน 00:05 Asia/Bangkok — ทุก instance ตื่นได้ ทำงานจริงตัวเดียว (ล็อกใน DB); ปิดด้วย {@code JOBS_ENABLED=false}. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "an22.app.jobs", havingValue = "true", matchIfMissing = true)
class BillingJob {

  private final BillingService billing;

  BillingJob(BillingService billing) {
    this.billing = billing;
  }

  @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Bangkok")
  void daily() {
    billing.runDaily();
  }
}
