package com.an22.api.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * แทน {@code /error} ของ Boot (error ที่เกิดนอก controller เช่นใน filter) ให้ได้ {@code { message }} รูปเดียวกับ
 * {@link ErrorHandler} — ไม่มี timestamp / path / trace.
 */
@RestController
public class FallbackErrorController implements ErrorController {

  @RequestMapping("${server.error.path:/error}")
  ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status =
        code instanceof Integer c && HttpStatus.resolve(c) != null
            ? HttpStatus.valueOf(c)
            : HttpStatus.INTERNAL_SERVER_ERROR;
    String message;
    if (status == HttpStatus.NOT_FOUND || status == HttpStatus.METHOD_NOT_ALLOWED) {
      status = HttpStatus.NOT_FOUND;
      Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
      String path = uri == null ? "" : uri.toString().replaceFirst("^" + request.getContextPath(), "");
      message = ErrorResponse.noEndpoint(request.getMethod(), path);
    } else if (status == HttpStatus.UNAUTHORIZED) {
      message = ErrorResponse.SESSION_EXPIRED;
    } else if (status.is4xxClientError()) {
      message = ErrorResponse.BAD_REQUEST;
    } else {
      message = ErrorResponse.SERVER_ERROR;
    }
    return ResponseEntity.status(status).body(new ErrorResponse(message));
  }
}
