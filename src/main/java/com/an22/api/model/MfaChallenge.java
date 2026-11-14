package com.an22.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * รหัสผ่านถูก ต่อด้วยรหัส Authenticator (E55): {@code mfa} = {@code verify} (มีแล้ว) / {@code setup} (สแกน {@code setup} ก่อน) +
 * {@code challenge} (JWT aud=mfa) ที่ an22-web เก็บไว้ใน session แล้วตัดออกก่อนถึง browser — เหมือน token.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MfaChallenge(String challenge, String mfa, MfaSetup setup) implements LoginResult {}
