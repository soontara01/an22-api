package com.an22.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** health ของ load balancer — ไม่ต้อง login, log ระดับ debug (RequestLogFilter). */
@RestController
public class HealthController {

  private static final Map<String, String> OK = Map.of("status", "ok");

  private final HealthService service;

  HealthController(HealthService service) {
    this.service = service;
  }

  /** liveness — ไม่แตะ DB (DB ล่มไม่ควรทำให้ instance ถูก restart). */
  @GetMapping("/healthz")
  Map<String, String> healthz() {
    return OK;
  }

  /** readiness — ping DB ผ่าน PgBouncer; ไม่พร้อม → 503. */
  @GetMapping("/readyz")
  Map<String, String> readyz() {
    service.checkDatabase();
    return OK;
  }
}
