package com.an22.api.model;

/** {@code DenominationCount}: จำนวนแบงก์ / เหรียญต่อมูลค่า (qty เป็น Double ให้กฎ "จำนวนเต็ม" ทำงาน). */
public record DenominationCount(Double value, Double qty) {}
