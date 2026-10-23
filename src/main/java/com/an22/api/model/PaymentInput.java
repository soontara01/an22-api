package com.an22.api.model;

/** {@code PaymentInput}: การชำระที่กรอกที่ POS — เงินสด {@code amount} = เงินที่รับมา. */
public record PaymentInput(Long methodId, Double amount, String reference, Integer installmentMonths) {}
