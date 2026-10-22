package com.an22.api.model;

/** {@code PosTerminal} (pos-terminal.model.ts): {@code id} = เลขเครื่อง (ผู้ใช้กำหนด ไม่ซ้ำในบริษัท, เปลี่ยนไม่ได้). */
public record PosTerminal(Integer id, String name, String posId, Long branchId, Boolean active) {}
