package com.an22.api.model;

import tools.jackson.databind.annotation.JsonDeserialize;

/** {@code PosTerminal} (pos-terminal.model.ts): {@code id} = เลขเครื่อง (ผู้ใช้กำหนด ไม่ซ้ำในบริษัท, เปลี่ยนไม่ได้). */
public record PosTerminal(@JsonDeserialize(using = IntegerOrInvalid.class) Integer id, String name, String posId, Long branchId, Boolean active) {}
