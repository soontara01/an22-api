package com.an22.api.model;

/** {@code SalePayment}: บรรทัดชำระของบิล (snapshot ช่องทาง, ยอดที่ตัด, เงินที่รับ). */
public record SalePayment(
    long methodId, String name, String type, double amount, double tendered, String reference, Integer installmentMonths) {}
