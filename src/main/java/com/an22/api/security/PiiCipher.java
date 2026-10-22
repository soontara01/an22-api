package com.an22.api.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * เข้ารหัสข้อมูลส่วนบุคคลในแอป (AES-256-GCM, nonce สุ่มต่อค่า) + HMAC-SHA256 ไว้ค้นหา.
 *
 * <p>รูปแบบที่เก็บ ({@code bytea}): {@code [version กุญแจ 1 byte][nonce 12 byte][ciphertext + tag 16 byte]} — อ่านได้ทุก
 * version ที่ตั้งไว้ เขียนด้วย version สูงสุด (หมุนกุญแจ: ให้ทุก instance อ่านกุญแจใหม่ได้ก่อน แล้วค่อยเพิ่มเป็นตัวสูงสุด).
 *
 * <p>AAD = {@link #aad(String, String, long)} ผูกค่ากับตาราง / ช่อง / บริษัท — copy ไปแถวอื่นแล้วถอดไม่ได้. ไม่มีค่ากุญแจ /
 * ค่าที่ถอดแล้วในข้อความ error ใด ๆ.
 */
public final class PiiCipher {

  private static final String AES = "AES/GCM/NoPadding";
  private static final String HMAC = "HmacSHA256";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_BYTES = 32;

  private final Map<Integer, SecretKeySpec> keys;
  private final int writeVersion;
  private final SecretKeySpec hashKey;
  private final SecureRandom random = new SecureRandom();

  /** @param keys version (1–255) → กุญแจ 32 byte; @param hashKey กุญแจ HMAC ≥ 32 byte (แยกจากกุญแจเข้ารหัส) */
  public PiiCipher(Map<Integer, byte[]> keys, byte[] hashKey) {
    if (keys.isEmpty()) {
      throw new IllegalStateException("ต้องตั้ง PII_KEYS");
    }
    TreeMap<Integer, SecretKeySpec> specs = new TreeMap<>();
    keys.forEach(
        (version, key) -> {
          if (version < 1 || version > 255) {
            throw new IllegalStateException("PII_KEYS: version ต้องอยู่ระหว่าง 1–255");
          }
          if (key.length != KEY_BYTES) {
            throw new IllegalStateException("PII_KEYS: กุญแจ version " + version + " ต้องยาว 32 byte");
          }
          specs.put(version, new SecretKeySpec(key, "AES"));
        });
    if (hashKey.length < KEY_BYTES) {
      throw new IllegalStateException("PII_HASH_KEY ต้องยาว ≥ 32 byte");
    }
    this.keys = Collections.unmodifiableMap(specs);
    this.writeVersion = specs.lastKey();
    this.hashKey = new SecretKeySpec(hashKey, HMAC);
  }

  /**
   * จากค่า env: {@code PII_KEYS} = {@code <version>:<base64 32 byte>,…}, {@code PII_HASH_KEY} = base64 ≥ 32 byte.
   *
   * @throws IllegalStateException ไม่ตั้ง / รูปแบบผิด (ข้อความไม่มีค่ากุญแจ)
   */
  public static PiiCipher fromEnv(String piiKeys, String piiHashKey) {
    if (piiKeys == null || piiKeys.isBlank()) {
      throw new IllegalStateException("ต้องตั้ง PII_KEYS (<version>:<base64 32 byte>,…)");
    }
    if (piiHashKey == null || piiHashKey.isBlank()) {
      throw new IllegalStateException("ต้องตั้ง PII_HASH_KEY (base64 ≥ 32 byte)");
    }
    Map<Integer, byte[]> keys = new TreeMap<>();
    for (String entry : piiKeys.split(",")) {
      String[] parts = entry.trim().split(":", 2);
      if (parts.length != 2) {
        throw new IllegalStateException("PII_KEYS ต้องเป็น <version>:<base64 32 byte> คั่นด้วย ,");
      }
      int version;
      try {
        version = Integer.parseInt(parts[0].trim());
      } catch (NumberFormatException e) {
        throw new IllegalStateException("PII_KEYS: version ต้องเป็นตัวเลข 1–255");
      }
      if (keys.put(version, decode("PII_KEYS", parts[1])) != null) {
        throw new IllegalStateException("PII_KEYS: version " + version + " ซ้ำ");
      }
    }
    return new PiiCipher(keys, decode("PII_HASH_KEY", piiHashKey));
  }

  private static byte[] decode(String name, String base64) {
    try {
      return Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(name + " ต้องเป็น base64");
    }
  }

  /** AAD มาตรฐาน: {@code <ตาราง>.<ช่อง>:<tenant_id>} เช่น {@code tax_invoices.buyer:1}. */
  public static String aad(String table, String column, long tenantId) {
    return table + "." + column + ":" + tenantId;
  }

  public byte[] encrypt(byte[] plaintext, String aad) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(AES);
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(writeVersion), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
          .put((byte) writeVersion)
          .put(nonce)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("เข้ารหัสข้อมูลไม่สำเร็จ", e);
    }
  }

  /** @throws PiiDecryptException ข้อมูลถูกแก้ / AAD ไม่ตรง / ไม่มีกุญแจ version นั้น */
  public byte[] decrypt(byte[] stored, String aad) {
    if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
      throw new PiiDecryptException("ข้อมูลเข้ารหัสสั้นเกินไป");
    }
    int version = Byte.toUnsignedInt(stored[0]);
    SecretKeySpec key = keys.get(version);
    if (key == null) {
      throw new PiiDecryptException("ไม่มีกุญแจ version " + version);
    }
    try {
      Cipher cipher = Cipher.getInstance(AES);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new PiiDecryptException("ถอดรหัสข้อมูลไม่ได้");
    }
  }

  public byte[] encryptString(String plaintext, String aad) {
    return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
  }

  public String decryptString(byte[] stored, String aad) {
    return new String(decrypt(stored, aad), StandardCharsets.UTF_8);
  }

  /**
   * HMAC-SHA256 ของ {@code <tenant_id>:<เลขที่ normalize แล้ว>} — ค้นผู้ซื้อด้วยเลขผู้เสียภาษี / บัตร / passport แบบตรงทั้งเลข;
   * มี tenant ใน hash: คนเดียวกันต่างบริษัทได้ค่าต่างกัน.
   */
  public byte[] hashTaxId(long tenantId, String taxId) {
    return hmac(tenantId + ":" + normalizeTaxId(taxId));
  }

  /** HMAC-SHA256 (กุญแจ {@code PII_HASH_KEY}) ของค่าใด ๆ — เก็บ key ที่ค้นหาได้โดยไม่เก็บค่าจริง (username / IP ของตัวนับ login ผิด). */
  public byte[] hmac(String value) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(hashKey);
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("คำนวณ hash ไม่สำเร็จ", e);
    }
  }

  /** ตัดช่องว่าง / {@code -}, ตัวใหญ่ ({@code 1-2345-67890-12-3} → {@code 1234567890123}). */
  static String normalizeTaxId(String taxId) {
    return taxId.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  /** ถอดรหัสไม่ได้ — ข้อความไม่มีค่าข้อมูล / กุญแจ. */
  public static final class PiiDecryptException extends RuntimeException {
    PiiDecryptException(String message) {
      super(message);
    }
  }
}
