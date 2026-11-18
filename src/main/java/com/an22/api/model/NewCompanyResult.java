package com.an22.api.model;

/** {@code NewCompanyResult} (platform.model.ts, E59): คำตอบของ {@code POST /platform/companies}. */
public record NewCompanyResult(PlatformCompany.Detail company, SetupLink setupLink) {}
