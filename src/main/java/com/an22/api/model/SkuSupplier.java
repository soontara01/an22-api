package com.an22.api.model;

/** {@code SkuSupplier} (product.model.ts): เงื่อนไขซื้อของผู้จำหน่าย 1 รายของ SKU — {@code cost} ไม่รวม VAT ต่อหน่วยฐาน. */
public record SkuSupplier(Long supplierId, String supplierSku, Double cost, Integer leadTimeDays, Integer moq, Boolean isMain) {}
