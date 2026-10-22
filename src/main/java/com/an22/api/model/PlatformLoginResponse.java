package com.an22.api.model;

/** {@code POST /platform/auth/login}: admin + {@code token} (web เก็บ token ช่อง platform, E48). */
public record PlatformLoginResponse(String token, PlatformAdmin admin) {}
