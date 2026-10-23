package com.an22.api.model;

import java.util.List;

/**
 * {@code TransferPayload} ({@code POST /transfers} ที่สาขาต้นทาง) — qty เป็น Double ให้กฎ "จำนวนเต็ม" ทำงาน (Jackson ตัดทศนิยมเงียบถ้าเป็น
 * Integer).
 */
public record TransferPayload(Long toBranchId, List<Line> lines, String note) {

  public record Line(Long productId, Double qty, List<String> serials) {}
}
