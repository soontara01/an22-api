package com.an22.api.pos;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.HeldBill;
import com.an22.api.web.BranchContext;
import com.an22.api.web.TerminalContext;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/held-bills} (E48) — ทุกคนที่ทำงานที่เครื่อง / สาขานี้ได้. */
@RestController
public class HeldBillController {

  private final HeldBillService service;

  HeldBillController(HeldBillService service) {
    this.service = service;
  }

  @GetMapping("/pos/held-bills")
  List<HeldBill> list(ShopSession s, BranchContext ctx, TerminalContext terminal) {
    return service.list(s, ctx, terminal.terminalId());
  }

  @PostMapping("/pos/held-bills")
  HeldBill hold(ShopSession s, BranchContext ctx, TerminalContext terminal, @RequestBody HeldBill body) {
    return service.hold(s, ctx, terminal.terminalId(), body);
  }

  @DeleteMapping("/pos/held-bills/{id}")
  void remove(BranchContext ctx, TerminalContext terminal, @PathVariable String id) {
    service.remove(ctx, terminal.terminalId(), id);
  }
}
