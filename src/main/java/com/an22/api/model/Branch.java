package com.an22.api.model;

/** {@code Branch} (company.model.ts). */
public record Branch(
    long id,
    String code,
    String name,
    String branchNo,
    String address,
    String phone,
    String receiptFooter,
    boolean active) {}
