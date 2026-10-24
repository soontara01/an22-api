package com.an22.api.model;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * field {@code Integer} ขาเข้าที่ mock มีกฎ "จำนวนเต็ม" ของตัวเอง (E2E B3): ตัวเลขที่ไม่ใช่จำนวนเต็ม (2.5) / เกิน int → {@link #INVALID} ซึ่งอยู่นอกช่วงของ
 * กฎทุกตัวที่ใช้ → ได้ข้อความของกฎ (= mock {@code Number.isInteger()}) แทนที่ Jackson จะตัดเป็น 2 เงียบ ๆ; 7.0 = 7. field {@code Integer} อื่น
 * ทศนิยม → 400 ข้อความกลาง ({@code accept-float-as-int: false}).
 */
public final class IntegerOrInvalid extends StdDeserializer<Integer> {

  public static final int INVALID = Integer.MIN_VALUE;

  public IntegerOrInvalid() {
    super(Integer.class);
  }

  @Override
  public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
    JsonToken token = p.currentToken();
    if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
      return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
    }
    BigDecimal n = p.getDecimalValue();
    try {
      return n.intValueExact();
    } catch (ArithmeticException e) {
      return INVALID;
    }
  }
}
