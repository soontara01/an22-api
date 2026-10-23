package com.an22.api.model;

/** {@code ManualDiscount} (ส่วนลดพิเศษ): kind percent = % ของราคาหลังโปร, amount = บาท. */
public record ManualDiscount(String kind, Double value) {}
