package com.an22.api.model;

import com.an22.api.rules.PlanRules;
import java.util.List;

/** {@code SubscriptionInfo} (subscription.model.ts) ของ {@code GET /company/subscription} — ใบแจ้งหนี้ใหม่สุดก่อน. */
public record SubscriptionInfo(
    String planId, PlanRules.Usage usage, String status, Subscription subscription, List<BillingInvoice> invoices) {}
