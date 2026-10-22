package com.an22.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** {@code BillingInvoice} (subscription.model.ts) — ใบแจ้งหนี้ค่าบริการ SaaS (E35). */
public record BillingInvoice(
    long id,
    long companyId,
    String invoiceNo,
    String planId,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal amount,
    LocalDate issuedOn,
    LocalDate dueDate,
    String status,
    Instant paidAt) {}
