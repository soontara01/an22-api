package com.an22.api.auth;

/**
 * key ของตัวนับ login ผิด: scope {@code account} (ร้าน = รหัสบริษัท + username ที่พิมพ์ / platform = username) หรือ {@code ip}.
 * เก็บใน DB เป็น HMAC เท่านั้น.
 */
public record ThrottleKey(String scope, String value) {

  public static ThrottleKey shop(String companyCode, String username) {
    return new ThrottleKey("account", "shop:" + companyCode + ":" + username);
  }

  public static ThrottleKey platform(String username) {
    return new ThrottleKey("account", "platform:" + username);
  }

  public static ThrottleKey ip(String ip) {
    return new ThrottleKey("ip", "ip:" + ip);
  }
}
