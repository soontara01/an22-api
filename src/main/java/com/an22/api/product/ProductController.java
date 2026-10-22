package com.an22.api.product;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.BranchStockRow;
import com.an22.api.model.Product;
import com.an22.api.model.SerialNumber;
import com.an22.api.model.SerialReceiveResult;
import com.an22.api.model.StockCardResult;
import com.an22.api.product.ProductService.SerialReceive;
import com.an22.api.product.ProductService.SerialRemove;
import com.an22.api.product.ProductService.StockAdjust;
import com.an22.api.web.BranchContext;
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

/** {@code /products} (SKU / สต็อก / serial) — ทุกคนแก้ SKU ได้, ตัดสต็อกออก (stock delta &lt; 0, serials/remove) เฉพาะ admin. */
@RestController
public class ProductController {

  static final String STOCK_OUT_ADMIN = "ตัดสต็อกออกได้เฉพาะผู้ดูแลระบบ";

  private final ProductService service;

  ProductController(ProductService service) {
    this.service = service;
  }

  @GetMapping("/products")
  List<Product> list(BranchContext branch) {
    return service.list(branch);
  }

  @PostMapping("/products")
  Product create(ShopSession s, BranchContext branch, @RequestBody Product body) {
    return service.create(s, branch, body);
  }

  @GetMapping("/products/{id}")
  Product get(BranchContext branch, @PathVariable long id) {
    return service.get(branch, id);
  }

  @PutMapping("/products/{id}")
  Product update(ShopSession s, BranchContext branch, @PathVariable long id, @RequestBody Product body) {
    return service.update(s, branch, id, body);
  }

  @DeleteMapping("/products/{id}")
  void delete(@PathVariable long id) {
    service.delete(id);
  }

  @PutMapping("/products/{id}/stock")
  Product adjustStock(ShopSession s, BranchContext branch, @PathVariable long id, @RequestBody StockAdjust body) {
    if (body.delta() != null && body.delta() < 0) {
      Permissions.requireAdmin(s, STOCK_OUT_ADMIN);
    }
    return service.adjustStock(s, branch, id, body);
  }

  @GetMapping("/products/{id}/movements")
  StockCardResult movements(
      ShopSession s,
      BranchContext branch,
      @PathVariable long id,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String branchId) {
    return service.movements(s, branch, id, from, to, branchId);
  }

  @GetMapping("/products/{id}/branch-stock")
  List<BranchStockRow> branchStock(@PathVariable long id) {
    return service.branchStock(id);
  }

  @GetMapping("/products/{id}/serials")
  List<SerialNumber> serials(BranchContext branch, @PathVariable long id) {
    return service.serials(branch, id);
  }

  @PostMapping("/products/{id}/serials")
  SerialReceiveResult receiveSerials(BranchContext branch, @PathVariable long id, @RequestBody SerialReceive body) {
    return service.receiveSerials(branch, id, body);
  }

  @PostMapping("/products/{id}/serials/remove")
  Product removeSerials(ShopSession s, BranchContext branch, @PathVariable long id, @RequestBody SerialRemove body) {
    Permissions.requireAdmin(s, STOCK_OUT_ADMIN);
    return service.removeSerials(s, branch, id, body);
  }
}
