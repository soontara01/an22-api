package com.an22.api.model;

import java.time.Instant;
import java.util.List;

/**
 * {@code Sale} (sale.model.ts) — เลข {@code POS-<สาขา>-<เครื่อง>-YYYYMMDD-NNNN}; returnStatus / creditedTotal / creditNoteNos / exchangeNos
 * คำนวณจากใบลดหนี้ / ใบเปลี่ยนทุกครั้งที่อ่าน.
 */
public record Sale(
    long id,
    String orderNo,
    Instant date,
    String cashier,
    String customer,
    List<SaleLine> lines,
    List<SalePayment> payments,
    double subtotal,
    double itemDiscount,
    double billDiscount,
    double total,
    double vat,
    double rounding,
    double change,
    List<Long> billPromotionIds,
    int itemCount,
    String status,
    Instant voidedAt,
    String voidReason,
    String taxInvoiceNo,
    String returnStatus,
    double creditedTotal,
    List<String> creditNoteNos,
    List<String> exchangeNos,
    double manualDiscount,
    String manualDiscountReason,
    String manualDiscountBy,
    Long shiftId,
    Long voidShiftId,
    String voidedBy,
    String voidApprovedBy,
    Long branchId,
    Integer terminalId,
    String posId,
    boolean vatRegistered) {}
