package com.an22.api.price;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.SkuPrice;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /prices} — อ่านได้ทุกคน, เขียนเฉพาะ admin (ADMIN_WRITES ของ mock). */
@RestController
public class PriceController {

  static final String PRICE_ADMIN = "แก้ไขราคาขายได้เฉพาะผู้ดูแลระบบ";

  private final PriceService service;

  PriceController(PriceService service) {
    this.service = service;
  }

  /** {@code ?productId=} ไม่ใช่ตัวเลข / 0 = ทุก SKU ({@code Number(x) || null} ของ mock). */
  @GetMapping("/prices")
  List<SkuPrice> list(@RequestParam(required = false) String productId) {
    Long id = null;
    try {
      id = productId == null ? null : Long.valueOf(productId.strip());
    } catch (NumberFormatException e) {
      // เหมือน mock: ค่าที่ไม่ใช่ตัวเลข = ไม่กรอง
    }
    return service.list(id == null || id == 0 ? null : id);
  }

  @PostMapping("/prices")
  SkuPrice create(ShopSession s, @RequestBody SkuPrice body) {
    Permissions.requireAdmin(s, PRICE_ADMIN);
    return service.create(s, body);
  }

  @PutMapping("/prices/{id}")
  SkuPrice update(ShopSession s, @PathVariable long id, @RequestBody SkuPrice body) {
    Permissions.requireAdmin(s, PRICE_ADMIN);
    return service.update(s, id, body);
  }

  @DeleteMapping("/prices/{id}")
  void delete(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, PRICE_ADMIN);
    service.delete(s, id);
  }
}
