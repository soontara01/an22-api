package com.an22.api.model;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code PaymentMethod} / {@code PaymentMethodPayload} (payment-method.model.ts) — ตัวเลขเป็น Double ให้กฎตรวจแบบ JS ได้ตรง
 * ({@code moneyInputError()}); ขาเข้า field ที่ไม่ส่ง = null → ค่าเริ่ม ({@code PAYMENT_METHOD_DEFAULTS}).
 */
public record PaymentMethod(
    Long id,
    String code,
    String name,
    String type,
    Boolean active,
    Integer sortOrder,
    Boolean requireReference,
    String referenceLabel,
    Double minAmount,
    Double maxAmount,
    Double feePercent,
    String cashRounding,
    String promptPayId,
    String bankAccount,
    @JsonDeserialize(contentUsing = IntegerOrInvalid.class) List<Integer> installmentMonths,
    String closeCheck,
    String note) {}
