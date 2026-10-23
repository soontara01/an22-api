package com.an22.api.model;

import java.util.List;

/** {@code TransferLine}: qty เป็นหน่วยฐาน, unitCost = ทุนโอนต่อหน่วย (serial = เฉลี่ยทุนของเครื่อง), receivedQty null = ยังไม่รับ. */
public record TransferLine(
    long productId,
    String sku,
    String name,
    String unit,
    int qty,
    List<String> serials,
    double unitCost,
    Integer receivedQty,
    List<String> receivedSerials) {}
