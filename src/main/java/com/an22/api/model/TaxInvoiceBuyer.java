package com.an22.api.model;

/** {@code TaxInvoiceBuyer}: idType tax_id / passport, branchType head / branch / none — PII (เก็บเข้ารหัส). */
public record TaxInvoiceBuyer(String name, String idType, String taxId, String branchType, String branchNo, String address) {}
