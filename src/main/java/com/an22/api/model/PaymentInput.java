package com.an22.api.model;

import tools.jackson.databind.annotation.JsonDeserialize;

/** {@code PaymentInput}: การชำระที่กรอกที่ POS — เงินสด {@code amount} = เงินที่รับมา. */
public record PaymentInput(Long methodId, Double amount, String reference, @JsonDeserialize(using = IntegerOrInvalid.class) Integer installmentMonths) {}
