package com.an22.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PiiCipherTest {

  private static final byte[] KEY_1 = random(32);
  private static final byte[] KEY_2 = random(32);
  private static final byte[] HASH_KEY = random(32);
  private static final String AAD = PiiCipher.aad("tax_invoices", "buyer", 1);

  private final PiiCipher cipher = new PiiCipher(Map.of(1, KEY_1), HASH_KEY);

  @Test
  void decryptsWhatItEncrypts() {
    byte[] stored = cipher.encryptString("บริษัท ผู้ซื้อ จำกัด", AAD);

    assertThat(cipher.decryptString(stored, AAD)).isEqualTo("บริษัท ผู้ซื้อ จำกัด");
    assertThat(stored[0]).isEqualTo((byte) 1);
  }

  @Test
  void sameValueTwiceGivesDifferentBytes() {
    assertThat(cipher.encryptString("x", AAD)).isNotEqualTo(cipher.encryptString("x", AAD));
  }

  @Test
  void anyChangedByteFailsToDecrypt() {
    byte[] stored = cipher.encryptString("ข้อมูล", AAD);
    for (int i = 1; i < stored.length; i++) {
      byte[] tampered = stored.clone();
      tampered[i] ^= 0x01;
      assertThatThrownBy(() -> cipher.decrypt(tampered, AAD)).isInstanceOf(PiiCipher.PiiDecryptException.class);
    }
  }

  @Test
  void otherTenantOrColumnAadFailsToDecrypt() {
    byte[] stored = cipher.encryptString("ข้อมูล", AAD);

    assertThatThrownBy(() -> cipher.decrypt(stored, PiiCipher.aad("tax_invoices", "buyer", 2)))
        .isInstanceOf(PiiCipher.PiiDecryptException.class);
    assertThatThrownBy(() -> cipher.decrypt(stored, PiiCipher.aad("credit_notes", "buyer", 1)))
        .isInstanceOf(PiiCipher.PiiDecryptException.class);
  }

  @Test
  void oldKeyStillReadsAfterAddingNewVersion() {
    byte[] old = cipher.encryptString("เก่า", AAD);
    PiiCipher rotated = new PiiCipher(Map.of(1, KEY_1, 2, KEY_2), HASH_KEY);

    assertThat(rotated.decryptString(old, AAD)).isEqualTo("เก่า");
    byte[] fresh = rotated.encryptString("ใหม่", AAD);
    assertThat(fresh[0]).isEqualTo((byte) 2);
    assertThat(rotated.decryptString(fresh, AAD)).isEqualTo("ใหม่");
    // ตัวที่ยังไม่รู้จักกุญแจ 2 อ่านไม่ได้ (หมุน: ให้ทุก instance อ่านได้ก่อนค่อยเขียนด้วยตัวใหม่)
    assertThatThrownBy(() -> cipher.decrypt(fresh, AAD))
        .isInstanceOf(PiiCipher.PiiDecryptException.class)
        .hasMessage("ไม่มีกุญแจ version 2");
  }

  @Test
  void taxIdHashIgnoresFormattingButNotTenant() {
    assertThat(cipher.hashTaxId(1, "1-2345-67890-12-3")).isEqualTo(cipher.hashTaxId(1, "1234567890123"));
    assertThat(cipher.hashTaxId(1, " ab 123 ")).isEqualTo(cipher.hashTaxId(1, "AB123"));
    assertThat(cipher.hashTaxId(1, "1234567890123")).isNotEqualTo(cipher.hashTaxId(2, "1234567890123"));
    assertThat(cipher.hashTaxId(1, "1234567890123")).hasSize(32);
  }

  @Test
  void fromEnvParsesVersionsAndWritesWithHighest() {
    String env = "1:" + b64(KEY_1) + ", 2:" + b64(KEY_2);
    PiiCipher fromEnv = PiiCipher.fromEnv(env, b64(HASH_KEY));

    assertThat(fromEnv.encryptString("x", AAD)[0]).isEqualTo((byte) 2);
    assertThat(fromEnv.decryptString(cipher.encryptString("x", AAD), AAD)).isEqualTo("x");
  }

  @Test
  void missingOrBadEnvRefusesWithoutLeakingKey() {
    String key = b64(KEY_1);
    assertThatThrownBy(() -> PiiCipher.fromEnv(null, key)).hasMessageContaining("PII_KEYS");
    assertThatThrownBy(() -> PiiCipher.fromEnv("1:" + key, "")).hasMessageContaining("PII_HASH_KEY");
    assertThatThrownBy(() -> PiiCipher.fromEnv(key, key))
        .hasMessageContaining("PII_KEYS")
        .hasMessageNotContaining(key);
    String shortKey = b64(random(16));
    assertThatThrownBy(() -> PiiCipher.fromEnv("1:" + shortKey, key))
        .hasMessageContaining("32 byte")
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> PiiCipher.fromEnv("1:" + key, shortKey))
        .hasMessageContaining("PII_HASH_KEY")
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> PiiCipher.fromEnv("1:" + key + ",1:" + key, key)).hasMessageContaining("ซ้ำ");
    assertThatThrownBy(() -> PiiCipher.fromEnv("0:" + key, key)).hasMessageContaining("1–255");
  }

  private static byte[] random(int n) {
    byte[] bytes = new byte[n];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
