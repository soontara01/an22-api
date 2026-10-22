package com.an22.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** ทุก error → {@code { message }} — 500 ไม่เปิดเผยรายละเอียด (stack trace อยู่ใน log ฝั่ง server เท่านั้น). */
@RestControllerAdvice
public class ErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> api(ApiException e) {
    return respond(e.status(), e.getMessage());
  }

  /** path ไม่มี / method ไม่รองรับ → 404 แบบเดียวกับ mock. */
  @ExceptionHandler({
    NoResourceFoundException.class,
    NoHandlerFoundException.class,
    HttpRequestMethodNotSupportedException.class
  })
  ResponseEntity<ErrorResponse> noEndpoint(HttpServletRequest request) {
    return respond(HttpStatus.NOT_FOUND, ErrorResponse.noEndpoint(request.getMethod(), apiPath(request)));
  }

  /** id ใน path อ่านไม่ได้ ({@code /users/abc}) = ไม่มี endpoint นี้ (mock จับ path ด้วย regex ตัวเลข); อื่น = 400. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    if (e.getParameter().hasParameterAnnotation(PathVariable.class)) {
      return noEndpoint(request);
    }
    return badRequest();
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HttpMediaTypeNotSupportedException.class,
    MethodArgumentNotValidException.class,
    ServletRequestBindingException.class
  })
  ResponseEntity<ErrorResponse> badRequest() {
    return respond(HttpStatus.BAD_REQUEST, ErrorResponse.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
    log.error("unhandled {} {}", request.getMethod(), apiPath(request), e);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.SERVER_ERROR);
  }

  /** path หลังตัด context path ({@code /api}). */
  public static String apiPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context != null && uri.startsWith(context) ? uri.substring(context.length()) : uri;
  }

  private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(message));
  }
}
