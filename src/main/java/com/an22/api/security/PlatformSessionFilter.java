package com.an22.api.security;

import com.an22.api.db.TxActor;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.platform.PlatformAuthService;
import com.an22.api.web.ApiException;
import com.an22.api.web.RequestLogFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * ทุก request ของ {@code /platform/**} หลังตรวจ JWT (aud = platform): admin ต้องมีอยู่จริงใน DB → request attribute
 * {@link #ATTR}; {@link TxActor} = platform (trigger บันทึกผู้ทำเป็น platform admin). ไม่ใช่ bean (สร้างใน SecurityConfig).
 */
public class PlatformSessionFilter extends OncePerRequestFilter {

  public static final String ATTR = PlatformAdmin.class.getName();
  public static final String SESSION_EXPIRED = "เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่";

  private final PlatformAuthService service;
  private final HandlerExceptionResolver errors;

  PlatformSessionFilter(PlatformAuthService service, HandlerExceptionResolver errors) {
    this.service = service;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) {
      chain.doFilter(request, response);
      return;
    }
    Optional<PlatformAdmin> admin = Optional.empty();
    try {
      long adminId = Long.parseLong(token.getToken().getSubject());
      TxActor.set(TxActor.platform(adminId));
      admin = service.current(adminId);
    } catch (NumberFormatException e) {
      // sub ผิดรูป = ไม่มี admin
    }
    if (admin.isEmpty()) {
      TxActor.clear();
      errors.resolveException(request, response, null, new ApiException(HttpStatus.UNAUTHORIZED, SESSION_EXPIRED));
      return;
    }
    request.setAttribute(ATTR, admin.get());
    request.setAttribute(RequestLogFilter.ATTR_USER_ID, admin.get().id());
    try {
      chain.doFilter(request, response);
    } finally {
      TxActor.clear();
    }
  }
}
