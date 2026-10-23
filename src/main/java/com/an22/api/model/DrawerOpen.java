package com.an22.api.model;

import java.time.Instant;

/** {@code DrawerOpen}: เปิดลิ้นชักโดยไม่มีการขาย (S7). */
public record DrawerOpen(long id, long shiftId, String reason, String by, Instant at) {}
