package com.an22.api.model;

import java.time.LocalDate;

/** {@code Subscription} (subscription.model.ts): {@code trialEndsOn} null = ไม่เคยทดลองใช้. */
public record Subscription(LocalDate trialEndsOn, LocalDate nextInvoiceOn) {}
