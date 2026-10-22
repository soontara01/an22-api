package com.an22.api.model;

import java.util.List;

/** {@code SerialReceiveResult} (serial.model.ts): {@code fromOtherBranch} = serial ที่อยู่สาขาอื่นล่าสุด (E32 — แนะนำใบโอน). */
public record SerialReceiveResult(Product product, List<SerialNumber> received, List<FromOtherBranch> fromOtherBranch) {

  public record FromOtherBranch(String serial, String branchCode) {}
}
