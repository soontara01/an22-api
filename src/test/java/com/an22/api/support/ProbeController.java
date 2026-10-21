package com.an22.api.support;

import com.an22.api.web.ApiException;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** endpoint สำหรับ test เท่านั้น (อยู่ใน src/test) — ลอง error format / JSON / access log. */
@RestController
public class ProbeController {

  public record Nulls(String empty, Instant at) {}

  @PostMapping("/test-probe/echo")
  JsonNode echo(@RequestBody JsonNode body) {
    return body;
  }

  @PostMapping("/test-probe/reject")
  JsonNode reject(@RequestBody JsonNode body) {
    throw ApiException.badRequest("ปฏิเสธตามกฎ");
  }

  @GetMapping("/test-probe/boom")
  JsonNode boom() {
    throw new IllegalStateException("รายละเอียดภายในที่ห้ามหลุด");
  }

  @GetMapping("/test-probe/nulls")
  Nulls nulls() {
    return new Nulls(null, Instant.parse("2026-10-21T03:04:05Z"));
  }
}
