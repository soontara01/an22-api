package com.an22.api.model;

/** {@code POST /auth/login} (E55): session + token ({@link LoginResponse}) หรือขั้นรหัส Authenticator ก่อน ({@link MfaChallenge}). */
public sealed interface LoginResult permits LoginResponse, MfaChallenge {}
