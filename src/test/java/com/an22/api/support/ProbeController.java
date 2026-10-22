package com.an22.api.support;

import com.an22.api.auth.ShopSession;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import com.an22.api.web.TerminalContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** endpoint สำหรับ test เท่านั้น (อยู่ใน src/test) — ลอง error format / JSON / access log. */
@RestController
public class ProbeController {

  private final ProbeService probe;

  ProbeController(ProbeService probe) {
    this.probe = probe;
  }

  /** endpoint ของร้านที่ผ่าน ShopSessionFilter ครบ (สาขา / เครื่อง / รอลบ) + ค่า app.* ใน transaction ของ request. */
  @GetMapping("/test-probe/session")
  Map<String, Object> session(ShopSession session, BranchContext branch, TerminalContext terminal) {
    Map<String, Object> out = new HashMap<>();
    out.put("tenantId", session.tenantId());
    out.put("userId", session.user().id());
    out.put("branchId", branch.branchId());
    out.put("terminalId", terminal.terminalId());
    out.put("db", probe.dbSession());
    return out;
  }

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
