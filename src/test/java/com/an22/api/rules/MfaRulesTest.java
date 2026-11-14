package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** {@link MfaRules} / {@link TotpRules} ที่ fixture ไม่ได้ครอบ (mfa.model.spec.ts / totp.model.spec.ts). */
class MfaRulesTest {

  @Test
  void requiresByTheCompanyRule() {
    assertThat(MfaRules.mfaRequiredFor("owner", "none")).isFalse();
    assertThat(MfaRules.mfaRequiredFor("owner", "admins")).isTrue();
    assertThat(MfaRules.mfaRequiredFor("admin", "admins")).isTrue();
    assertThat(MfaRules.mfaRequiredFor("user", "admins")).isFalse();
    assertThat(MfaRules.mfaRequiredFor("user", "all")).isTrue();
    assertThat(MfaRules.mfaRequiredFor("user", null)).isFalse();
  }

  @Test
  void codeStepWhenAskedOrEnabledOrRequired() {
    assertThat(MfaRules.loginNeedsMfa("user", false, "none", false)).isFalse();
    assertThat(MfaRules.loginNeedsMfa("user", false, "none", true)).isTrue();
    assertThat(MfaRules.loginNeedsMfa("user", true, "none", false)).isTrue();
    assertThat(MfaRules.loginNeedsMfa("user", false, "all", false)).isTrue();
  }

  @Test
  void disableAndResetMessages() {
    assertThat(MfaRules.mfaDisableError("user", false, "none")).isEqualTo("ยังไม่ได้เปิดใช้ Microsoft Authenticator");
    assertThat(MfaRules.mfaDisableError("admin", true, "admins")).isEqualTo("บริษัทบังคับให้ใช้ Microsoft Authenticator — ปิดเองไม่ได้");
    assertThat(MfaRules.mfaDisableError("user", true, "admins")).isNull();
    assertThat(MfaRules.mfaResetError(1, true, 1)).isEqualTo("ล้าง Microsoft Authenticator ของตัวเองไม่ได้ — ปิดได้ที่หน้าตั้งค่า");
    assertThat(MfaRules.mfaResetError(2, false, 1)).isEqualTo("ผู้ใช้นี้ยังไม่ได้เปิดใช้ Microsoft Authenticator");
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
