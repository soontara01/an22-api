package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** {@link MfaRules} / {@link TotpRules} ที่ fixture ไม่ได้ครอบ (mfa.model.spec.ts / totp.model.spec.ts). */
class MfaRulesTest {

  @Test
  void resetMessages() {
    assertThat(MfaRules.mfaResetError(1, true, 1)).isEqualTo("ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ให้ผู้ดูแลระบบคนอื่นล้างให้");
    assertThat(MfaRules.mfaResetError(2, false, 1)).isEqualTo("ผู้ใช้นี้ยังไม่ได้ผูก Microsoft Authenticator");
    assertThat(MfaRules.mfaResetError(2, true, 1)).isNull();
  }

  @Test
  void base32AndSecretsAndUrl() {
    assertThat(TotpRules.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    assertThat(new String(TotpRules.base32Decode("mzxw 6ytb oi=="), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    assertThat(TotpRules.base32Decode("not base32!")).isNull();
    String secret = TotpRules.newSecret(new SecureRandom());
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(TotpRules.otpauthUrl("DEMO", "somchai", "ABCD"))
        .isEqualTo("otpauth://totp/AN22%3ADEMO%3Asomchai?secret=ABCD&issuer=AN22&algorithm=SHA1&digits=6&period=30");
    assertThat(TotpRules.isCode(" 123 456 ")).isTrue();
    assertThat(TotpRules.isCode("12345a")).isFalse();
  }
}
