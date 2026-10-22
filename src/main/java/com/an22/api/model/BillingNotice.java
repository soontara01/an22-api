package com.an22.api.model;

/** {@code BillingNotice} (subscription.model.ts): level = info / warn / error. */
public record BillingNotice(String level, String text) {}
