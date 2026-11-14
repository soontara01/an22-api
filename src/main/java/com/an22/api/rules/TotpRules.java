package com.an22.api.rules;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * port จาก {@code core/models/totp.model.ts} (E55): TOTP RFC 6238 แบบที่ Microsoft Authenticator อ่าน — HMAC-SHA1, 6 หลัก, ช่วง 30 วินาที,
 * secret เป็น base32. ผลต้องตรง golden fixture {@code totp.json}. เวลา (ms) มาจาก {@code Clock} ของผู้เรียกเสมอ.
 */
public final class TotpRules {

  public static final int PERIOD_SECONDS = 30;
  public static final int DIGITS = 6;
  /** ช่วงก่อน / หลังตอนนี้ที่ยังรับ (นาฬิกามือถือคลาด) = ±30 วินาที. */
  public static final int WINDOW = 1;
  public static final String ISSUER = "AN22";

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private TotpRules() {}

  /** RFC 4648 base32 ไม่มี padding. */
  public static String base32Encode(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    int bits = 0;
    int value = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((value >>> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((value << (5 - bits)) & 31));
    }
    return out.toString();
  }

  /** base32 → byte (ไม่สนช่องว่าง / {@code =} / ตัวพิมพ์); null = ไม่ใช่ base32. */
  public static byte[] base32Decode(String text) {
    String clean = text.replaceAll("[\\s=]", "").toUpperCase(Locale.ROOT);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int bits = 0;
    int value = 0;
    for (char ch : clean.toCharArray()) {
      int index = BASE32.indexOf(ch);
      if (index < 0) {
        return null;
      }
      value = (value << 5) | index;
      bits += 5;
      if (bits >= 8) {
        out.write((value >>> (bits - 8)) & 255);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  /** secret ใหม่ 20 byte (160 bit) เป็น base32 32 ตัว. */
  public static String newSecret(SecureRandom random) {
    byte[] bytes = new byte[20];
    random.nextBytes(bytes);
    return base32Encode(bytes);
  }

  public static long step(long nowMs) {
    return Math.floorDiv(Math.floorDiv(nowMs, 1000L), PERIOD_SECONDS);
  }

  /** รหัสของช่วงหนึ่ง; {@code ""} เมื่อ secret ไม่ใช่ base32. */
  public static String code(String secret, long step) {
    byte[] key = base32Decode(secret);
    if (key == null) {
      return "";
    }
    byte[] counter = new byte[8];
    long rest = step;
    for (int i = 7; i >= 0; i--) {
      counter[i] = (byte) (rest & 0xff);
      rest >>>= 8;
    }
    byte[] mac;
    try {
      Mac hmac = Mac.getInstance("HmacSHA1");
      // HMAC รับ key ว่างไม่ได้ — secret ว่างไม่มีทางเกิดจากระบบ แต่กันไว้
      hmac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA1"));
      mac = hmac.doFinal(counter);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("คำนวณรหัสไม่สำเร็จ", e);
    }
    int offset = mac[19] & 15;
    int binary = ((mac[offset] & 127) << 24) | ((mac[offset + 1] & 0xff) << 16) | ((mac[offset + 2] & 0xff) << 8)
        | (mac[offset + 3] & 0xff);
    return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
  }

  /** ตัดช่องว่าง. */
  public static String normalizeCode(String code) {
    return code == null ? "" : code.replaceAll("\\s", "");
  }

  public static boolean isCode(String code) {
    return normalizeCode(code).matches("\\d{" + DIGITS + "}");
  }

  /** ช่วงที่รหัสตรงภายใน {@link #WINDOW} ของตอนนี้ หรือ null — ช่วงที่ ≤ {@code lastStep} (ใช้ไปแล้ว) ไม่รับอีก. */
  public static Long verify(String secret, String code, long nowMs, Long lastStep) {
    String typed = normalizeCode(code);
    if (!isCode(typed)) {
      return null;
    }
    long now = step(nowMs);
    for (long s = now - WINDOW; s <= now + WINDOW; s++) {
      if (lastStep != null && s <= lastStep) {
        continue;
      }
      if (code(secret, s).equals(typed)) {
        return s;
      }
    }
    return null;
  }

  /** สิ่งที่อยู่ใน QR: {@code otpauth://totp/AN22:<COMPANY>:<username>?secret=…&issuer=AN22&…} (เข้ารหัสแบบ encodeURIComponent). */
  public static String otpauthUrl(String companyCode, String username, String secret) {
    String label = encodeUriComponent(ISSUER + ":" + companyCode + ":" + username);
    return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encodeUriComponent(ISSUER) + "&algorithm=SHA1&digits="
        + DIGITS + "&period=" + PERIOD_SECONDS;
  }

  /** {@code encodeURIComponent()} ของ JS (URLEncoder ใช้ + แทนช่องว่าง และเข้ารหัส {@code ~ ' ( ) !}). */
  static String encodeUriComponent(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~");
  }
}
