package com.an22.api.model;

import java.time.Instant;
import java.util.Map;

/** {@code CompanyExport} (company-data.model.ts, E38): data = ชื่อ collection → record ตามที่เก็บ. */
public record CompanyExport(int schemaVersion, Instant exportedAt, Company company, Map<String, Object> data) {

  public record Company(long id, String code, String name, String taxId) {}
}
