package com.an22.api.masterdata;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Category;
import com.an22.api.model.CategoryPayload;
import com.an22.api.model.PaymentMethod;
import com.an22.api.model.Supplier;
import com.an22.api.web.Permissions;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /categories}, {@code /suppliers}, {@code /payment-methods} — อ่านได้ทุกคน, เขียนเฉพาะ admin (ADMIN_WRITES ของ mock). */
@RestController
public class MasterDataController {

  static final String CATEGORY_ADMIN = "แก้ไขหมวดหมู่ได้เฉพาะผู้ดูแลระบบ";
  static final String SUPPLIER_ADMIN = "แก้ไขผู้จำหน่ายได้เฉพาะผู้ดูแลระบบ";
  static final String METHOD_ADMIN = "แก้ไขช่องทางชำระเงินได้เฉพาะผู้ดูแลระบบ";

  public record Order(List<Long> ids) {}

  private final MasterDataService service;

  MasterDataController(MasterDataService service) {
    this.service = service;
  }

  @GetMapping("/categories")
  List<Category> categories() {
    return service.categories();
  }

  @PostMapping("/categories")
  Category createCategory(ShopSession s, @RequestBody CategoryPayload body) {
    Permissions.requireAdmin(s, CATEGORY_ADMIN);
    return service.createCategory(body);
  }

  @PutMapping("/categories/{id}")
  Category updateCategory(ShopSession s, @PathVariable long id, @RequestBody CategoryPayload body) {
    Permissions.requireAdmin(s, CATEGORY_ADMIN);
    return service.updateCategory(id, body);
  }

  @DeleteMapping("/categories/{id}")
  void deleteCategory(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, CATEGORY_ADMIN);
    service.deleteCategory(id);
  }

  @GetMapping("/suppliers")
  List<Supplier> suppliers() {
    return service.suppliers();
  }

  @GetMapping("/suppliers/{id}")
  Supplier supplier(@PathVariable long id) {
    return service.supplier(id);
  }

  @PostMapping("/suppliers")
  Supplier createSupplier(ShopSession s, @RequestBody Supplier body) {
    Permissions.requireAdmin(s, SUPPLIER_ADMIN);
    return service.createSupplier(body);
  }

  @PutMapping("/suppliers/{id}")
  Supplier updateSupplier(ShopSession s, @PathVariable long id, @RequestBody Supplier body) {
    Permissions.requireAdmin(s, SUPPLIER_ADMIN);
    return service.updateSupplier(id, body);
  }

  @DeleteMapping("/suppliers/{id}")
  void deleteSupplier(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, SUPPLIER_ADMIN);
    service.deleteSupplier(id);
  }

  @GetMapping("/payment-methods")
  List<PaymentMethod> paymentMethods() {
    return service.paymentMethods();
  }

  @PostMapping("/payment-methods")
  PaymentMethod createPaymentMethod(ShopSession s, @RequestBody PaymentMethod body) {
    Permissions.requireAdmin(s, METHOD_ADMIN);
    return service.createPaymentMethod(body);
  }

  @PutMapping("/payment-methods/order")
  List<PaymentMethod> reorder(ShopSession s, @RequestBody Order body) {
    Permissions.requireAdmin(s, METHOD_ADMIN);
    return service.reorderPaymentMethods(body.ids());
  }

  @PutMapping("/payment-methods/{id}")
  PaymentMethod updatePaymentMethod(ShopSession s, @PathVariable long id, @RequestBody PaymentMethod body) {
    Permissions.requireAdmin(s, METHOD_ADMIN);
    return service.updatePaymentMethod(id, body);
  }

  @DeleteMapping("/payment-methods/{id}")
  void deletePaymentMethod(ShopSession s, @PathVariable long id) {
    Permissions.requireAdmin(s, METHOD_ADMIN);
    service.deletePaymentMethod(id);
  }
}
