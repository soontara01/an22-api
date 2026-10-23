package com.an22.api.shift;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.CashMovement;
import com.an22.api.model.DrawerOpen;
import com.an22.api.model.Shift;
import com.an22.api.model.ShiftAdjustment;
import com.an22.api.model.ShiftPayloads;
import com.an22.api.rules.ShiftRules;
import com.an22.api.web.BranchContext;
import com.an22.api.web.ClientIp;
import com.an22.api.web.Permissions;
import com.an22.api.web.TerminalContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /shifts/*} (เครื่อง = {@code X-Terminal-Id}) — อนุมัติส่วนต่าง / ปรับปรุงกะเฉพาะ admin (adminOnlyError ของ mock ก่อน 404); สิทธิ์อื่น
 * (กะของตัวเอง P6, เจ้าของกะ / admin) ตรวจใน service ตามลำดับของ mock.
 */
@RestController
public class ShiftController {

  static final String APPROVE_ADMIN = "อนุมัติส่วนต่างได้เฉพาะผู้ดูแลระบบ";
  static final String ADJUST_ADMIN = "ปรับปรุงกะได้เฉพาะผู้ดูแลระบบ";

  private final ShiftService service;

  ShiftController(ShiftService service) {
    this.service = service;
  }

  @GetMapping("/shifts/current")
  Shift current(TerminalContext terminal) {
    return service.current(terminal.terminalId());
  }

  @PostMapping("/shifts/open")
  Shift open(ShopSession s, BranchContext ctx, TerminalContext terminal, @RequestBody ShiftPayloads.Open body) {
    return service.open(s, ctx, terminal.terminalId(), body);
  }

  @GetMapping("/shifts")
  List<Shift> list(ShopSession s, BranchContext ctx, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to, @RequestParam(required = false) String branchId,
      @RequestParam(required = false) String openOnly) {
    return service.list(s, ctx, from, to, branchId, "1".equals(openOnly));
  }

  @GetMapping("/shifts/last-float")
  Map<String, Double> lastFloat(TerminalContext terminal) {
    return Map.of("floatLeft", service.lastFloat(terminal.terminalId()));
  }

  @GetMapping("/shifts/variance-report")
  List<ShiftRules.VarianceReportRow> varianceReport(ShopSession s, BranchContext ctx, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
    return service.varianceReport(s, ctx, from, to, branchId);
  }

  @GetMapping("/shifts/{id}")
  Shift get(ShopSession s, @PathVariable long id) {
    return service.get(s, id);
  }

  @PostMapping("/shifts/{id}/lock")
  Shift lock(ShopSession s, TerminalContext terminal, @PathVariable long id) {
    return service.lock(s, id, terminal.terminalId());
  }

  @PostMapping("/shifts/{id}/unlock")
  Shift unlock(ShopSession s, @PathVariable long id, @RequestBody(required = false) ShiftPayloads.Credentials body,
      HttpServletRequest request) {
    return service.unlock(s, id, body, ClientIp.of(request));
  }

  @GetMapping("/shifts/{id}/summary")
  ShiftRules.ShiftSummary summary(ShopSession s, @PathVariable long id) {
    return service.summary(s, id);
  }

  @GetMapping("/shifts/{id}/movements")
  List<CashMovement> movements(ShopSession s, @PathVariable long id) {
    return service.movements(s, id);
  }

  @PostMapping("/shifts/{id}/cash-movements")
  CashMovement cashMovement(ShopSession s, TerminalContext terminal, @PathVariable long id,
      @RequestBody ShiftPayloads.CashMove body) {
    return service.cashMovement(s, id, terminal.terminalId(), body);
  }

  @GetMapping("/shifts/{id}/drawer-opens")
  List<DrawerOpen> drawerOpens(ShopSession s, @PathVariable long id) {
    return service.drawerOpens(s, id);
  }

  @PostMapping("/shifts/{id}/drawer-opens")
  DrawerOpen drawerOpen(ShopSession s, TerminalContext terminal, @PathVariable long id,
      @RequestBody(required = false) ShiftPayloads.DrawerOpenReason body) {
    return service.drawerOpen(s, id, terminal.terminalId(), body);
  }

  @PostMapping("/shifts/{id}/count-check")
  ShiftRules.CountCheckResult countCheck(ShopSession s, @PathVariable long id, @RequestBody(required = false) ShiftPayloads.Counts body) {
    return service.countCheck(s, id, body);
  }

  @PostMapping("/shifts/{id}/close")
  Shift close(ShopSession s, @PathVariable long id, @RequestBody ShiftPayloads.Close body, HttpServletRequest request) {
    return service.close(s, id, body, ClientIp.of(request));
  }

  @PostMapping("/shifts/{id}/approve")
  Shift approve(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, APPROVE_ADMIN);
    return service.approve(s, id);
  }

  @GetMapping("/shifts/{id}/adjustments")
  List<ShiftAdjustment> adjustments(ShopSession s, @PathVariable long id) {
    return service.adjustments(s, id);
  }

  @PostMapping("/shifts/{id}/adjustments")
  ShiftAdjustment adjust(ShopSession s, @PathVariable long id, @RequestBody ShiftPayloads.Adjust body) {
    Permissions.requireAdmin(s, ADJUST_ADMIN);
    return service.adjust(s, id, body);
  }
}
