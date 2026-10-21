package com.an22.api.web;

import org.springframework.http.HttpStatus;

/**
 * error ที่ผู้ใช้เห็น — ตอบ {@code { "message": "<ไทย>" }} ตาม api-contract.md (ข้อความตามกฎ / mock ทุกตัวอักษร).
 * เป็น RuntimeException → transaction ของ service rollback เอง.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }
}
