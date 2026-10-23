package com.an22.api.model;

import java.time.Instant;
import java.time.LocalDate;

/** {@code CompanyDeletion} (company-data.model.ts, E39): คำขอลบที่รออยู่ — purgeAfter = วันแรกที่ platform ลบได้. */
public record CompanyDeletion(Instant requestedAt, String requestedBy, String reason, LocalDate purgeAfter) {}
