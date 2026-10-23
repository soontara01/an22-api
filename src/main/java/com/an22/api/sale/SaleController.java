package com.an22.api.sale;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.Sale;
import com.an22.api.model.SalePayload;
import com.an22.api.model.TaxInvoice;
import com.an22.api.model.TaxInvoiceBuyer;
import com.an22.api.rules.PosPricing;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /sales}, ใบกำกับภาษีของบิล และ {@code /tax-invoices} — admin เท่านั้น: ออกใบใหม่แทนใบเดิม, รายงานภาษีขาย (adminOnlyError ของ mock ก่อน 404);
 * void ของคนที่ไม่ใช่ admin ต้องมีผู้อนุมัติ (service ตรวจหลัง 404 ตาม mock).
 */
@RestController
public class SaleController {

  static final String REISSUE_ADMIN = "ยกเลิกและออกใบกำกับภาษีใหม่ได้เฉพาะผู้ดูแลระบบ";
  static final String REPORT_ADMIN = "ดูรายงานภาษีขายได้เฉพาะผู้ดูแลระบบ";

  private final SaleService sales;
  private final TaxInvoiceService invoices;

  SaleController(SaleService sales, TaxInvoiceService invoices) {
    this.sales = sales;
    this.invoices = invoices;
  }

  @GetMapping("/sales")
  List<Sale> list(ShopSession s, BranchContext ctx, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String shiftId, @RequestParam(required = false) String branchId) {
    return sales.list(s, ctx, from, to, shiftId, branchId);
  }

  @PostMapping("/sales")
  Sale checkout(ShopSession s, BranchContext ctx, TerminalContext terminal, @RequestBody SalePayload body) {
    return sales.checkout(s, ctx, terminal.terminalId(), body);
  }

  @PostMapping("/sales/cost-check")
  Map<String, List<PosPricing.CartIssue>> costCheck(BranchContext ctx, @RequestBody SalePayload body) {
    return sales.costCheck(ctx, body);
  }

  @GetMapping("/sales/{id}")
  Sale get(@PathVariable long id) {
    return sales.get(id);
  }

  @PostMapping("/sales/{id}/void")
  Sale voidSale(ShopSession s, BranchContext ctx, TerminalContext terminal, @PathVariable long id,
      @RequestBody(required = false) SalePayload.VoidRequest body, HttpServletRequest request) {
    return sales.voidSale(s, ctx, terminal.terminalId(), id, body, ClientIp.of(request));
  }

  @PutMapping("/sales/{id}/status")
  Sale setStatus(@PathVariable long id, @RequestBody SalePayload.Status body) {
    return sales.setStatus(id, body);
  }

  @GetMapping("/sales/{id}/tax-invoice")
  TaxInvoice taxInvoice(ShopSession s, @PathVariable long id) {
    return invoices.current(s, id);
  }

  @GetMapping("/sales/{id}/tax-invoices")
  List<TaxInvoice> taxInvoices(ShopSession s, @PathVariable long id) {
    return invoices.all(s, id);
  }

  @PostMapping("/sales/{id}/tax-invoice")
  TaxInvoice issue(ShopSession s, BranchContext ctx, @PathVariable long id, @RequestBody(required = false) TaxInvoice.Request body) {
    return invoices.issue(s, ctx, id, body);
  }

  @PostMapping("/sales/{id}/tax-invoice/reissue")
  TaxInvoice reissue(ShopSession s, BranchContext ctx, @PathVariable long id, @RequestBody(required = false) TaxInvoice.Request body) {
    Permissions.requireAdmin(s, REISSUE_ADMIN);
    return invoices.reissue(s, ctx, id, body);
  }

  @GetMapping("/tax-invoices")
  List<TaxInvoice> report(ShopSession s, BranchContext ctx, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
    Permissions.requireAdmin(s, REPORT_ADMIN);
    return invoices.report(s, ctx, from, to, branchId);
  }

  @GetMapping("/tax-invoices/buyer")
  TaxInvoiceBuyer buyer(ShopSession s, @RequestParam(required = false) String taxId) {
    return invoices.lastBuyer(s, taxId);
  }
}
