package com.an22.api.model;

import java.util.List;

/** {@code TransferReceivePayload} ({@code POST /transfers/:id/receive} ที่สาขาปลายทาง): ของที่มาถึงจริงต่อบรรทัด. */
public record TransferReceivePayload(List<Line> lines, String shortReason) {

  public record Line(Long productId, Double receivedQty, List<String> receivedSerials) {}
}
