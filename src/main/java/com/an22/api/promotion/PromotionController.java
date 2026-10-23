package com.an22.api.promotion;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Promotion;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /promotions} — อ่านได้ทุกคน, เขียนเฉพาะ admin (ADMIN_WRITES ของ mock); โปรที่จำกัดสาขาเฉพาะ admin บริษัท (service ตรวจ). */
@RestController
public class PromotionController {

  static final String PROMOTION_ADMIN = "แก้ไขโปรโมชั่นได้เฉพาะผู้ดูแลระบบ";

  private final PromotionService service;

  PromotionController(PromotionService service) {
    this.service = service;
  }

  @GetMapping("/promotions")
  List<Promotion> list() {
    return service.list();
  }

  @GetMapping("/promotions/{id}")
  Promotion get(@PathVariable long id) {
    return service.get(id);
  }

  @PostMapping("/promotions")
  Promotion create(ShopSession s, @RequestBody Promotion body) {
    Permissions.requireAdmin(s, PROMOTION_ADMIN);
    return service.create(s, body);
  }

  @PutMapping("/promotions/{id}")
  Promotion update(ShopSession s, @PathVariable long id, @RequestBody Promotion body) {
    Permissions.requireAdmin(s, PROMOTION_ADMIN);
    return service.update(s, id, body);
  }

  @DeleteMapping("/promotions/{id}")
  void delete(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, PROMOTION_ADMIN);
    service.delete(s, id);
  }
}
