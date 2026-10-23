package com.an22.api.transfer;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Transfer;
import com.an22.api.model.TransferPayload;
import com.an22.api.model.TransferReceivePayload;
import com.an22.api.web.BranchContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /transfers} (E26): ทุกคนในสาขาสร้างที่ต้นทาง / รับที่ปลายทางได้; ยกเลิกเฉพาะ admin ต้นทาง — ตรวจใน service หลัง 404 / สิทธิ์ดู
 * ตามลำดับของ mock.
 */
@RestController
public class TransferController {

  public record Cancel(String reason) {}

  private final TransferService service;

  TransferController(TransferService service) {
    this.service = service;
  }

  @GetMapping("/transfers")
  List<Transfer> list(ShopSession s, BranchContext ctx, @RequestParam(required = false) String branchId,
      @RequestParam(required = false) String direction) {
    return service.list(s, ctx, branchId, direction);
  }

  @GetMapping("/transfers/{id}")
  Transfer get(ShopSession s, @PathVariable long id) {
    return service.get(s, id);
  }

  @PostMapping("/transfers")
  Transfer create(ShopSession s, BranchContext ctx, @RequestBody TransferPayload body) {
    return service.create(s, ctx, body);
  }

  @PostMapping("/transfers/{id}/receive")
  Transfer receive(ShopSession s, BranchContext ctx, @PathVariable long id, @RequestBody TransferReceivePayload body) {
    return service.receive(s, ctx, id, body);
  }

  @PostMapping("/transfers/{id}/cancel")
  Transfer cancel(ShopSession s, BranchContext ctx, @PathVariable long id, @RequestBody(required = false) Cancel body) {
    return service.cancel(s, ctx, id, body == null ? null : body.reason());
  }
}
