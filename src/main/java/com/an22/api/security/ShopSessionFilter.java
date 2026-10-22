package com.an22.api.security;

import com.an22.api.auth.AuthService;
import com.an22.api.auth.SessionService;
import com.an22.api.auth.ShopSession;
import com.an22.api.db.TxActor;
import com.an22.api.model.Branch;
import com.an22.api.web.ApiException;
import com.an22.api.web.BranchContext;
import com.an22.api.web.ErrorHandler;
import com.an22.api.web.ErrorResponse;
import com.an22.api.web.RequestLogFilter;
import com.an22.api.web.TerminalContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * ทุก request ของร้านหลังตรวจ JWT ผ่าน (ลำดับเดียวกับ {@code handle()} ของ mock):
 *
 * <ol>
 *   <li>{@link TxActor} = บริษัท + ผู้ใช้ของ token (ทุก transaction ของ request ได้ RLS ของบริษัทนี้)
 *   <li>อ่านผู้ใช้ + บริษัทจาก DB ({@link SessionService#load}) — ไม่ผ่าน → 401 (ยกเว้น {@code auth/verify} → 400 ข้อความเดียว)
 *   <li>บริษัทรอลบ (E39): นอก {@code /me}, {@code /company/*}, {@code /audit-log} → 403
 *   <li>{@code X-Branch-Id} (ยกเว้น {@code /me}): ไม่ใช่สาขาที่ใช้งาน / เข้าไม่ได้ → 403
 *   <li>{@code X-Terminal-Id} → {@link TerminalContext}
 * </ol>
 *
 * ไม่ใช่ bean (สร้างใน SecurityConfig) — กัน Spring Boot ลงทะเบียนเป็น servlet filter ซ้ำ.
 */
public class ShopSessionFilter extends OncePerRequestFilter {

  static final String DELETION_PENDING =
      "บริษัทอยู่ระหว่างรอลบข้อมูล — ดาวน์โหลดข้อมูลหรือยกเลิกคำขอลบได้ที่เมนูบัญชีบริษัท";
  static final String BRANCH_FORBIDDEN = "ไม่มีสิทธิ์ทำงานในสาขานี้";

  private final SessionService sessions;
  private final HandlerExceptionResolver errors;

  ShopSessionFilter(SessionService sessions, HandlerExceptionResolver errors) {
    this.sessions = sessions;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) {
      // ไม่มี token — AuthorizationFilter ถัดไปตอบผ่าน entry point
      chain.doFilter(request, response);
      return;
    }
    String path = ErrorHandler.apiPath(request);
    boolean verify = "/auth/verify".equals(path);
    try {
      Jwt jwt = token.getToken();
      Long tenantId = longClaim(jwt.getClaims().get(JwtService.CLAIM_TENANT));
      Long userId = longClaim(jwt.getSubject());
      if (tenantId == null || userId == null) {
        throw unauthenticated(verify);
      }
      TxActor.set(TxActor.user(tenantId, userId));
      TerminalContext terminal = TerminalContext.parse(request.getHeader("X-Terminal-Id"));
      Optional<ShopSession> loaded = sessions.load(tenantId, userId, terminal.terminalId());
      if (loaded.isEmpty()) {
        throw unauthenticated(verify);
      }
      ShopSession session = loaded.get();
      request.setAttribute(ShopSession.ATTR, session);
      request.setAttribute(RequestLogFilter.ATTR_TENANT_ID, tenantId);
      request.setAttribute(RequestLogFilter.ATTR_USER_ID, userId);

      BranchContext branch = new BranchContext(null);
      if (!verify && !"/me".equals(path)) {
        if (session.deletionPending() && !path.startsWith("/company/") && !"/audit-log".equals(path)) {
          throw ApiException.forbidden(DELETION_PENDING);
        }
        String header = request.getHeader("X-Branch-Id");
        if (header != null) {
          branch = new BranchContext(allowedBranch(session, header).orElseThrow(() -> ApiException.forbidden(BRANCH_FORBIDDEN)));
          request.setAttribute(RequestLogFilter.ATTR_BRANCH_ID, branch.branchId());
        }
      }
      request.setAttribute(BranchContext.ATTR, branch);
      request.setAttribute(TerminalContext.ATTR, terminal);
      request.setAttribute(RequestLogFilter.ATTR_TERMINAL_ID, terminal.terminalId());
    } catch (ApiException e) {
      TxActor.clear();
      errors.resolveException(request, response, null, e);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      TxActor.clear();
    }
  }

  /** สาขาใน header ที่ผู้ใช้ทำงานได้ (session.branches = สาขาที่ใช้งานอยู่ + canWorkIn). */
  private static Optional<Long> allowedBranch(ShopSession session, String header) {
    return session.branches().stream().map(Branch::id).filter(id -> id.toString().equals(header.strip())).findFirst();
  }

  private static ApiException unauthenticated(boolean verify) {
    return verify
        ? ApiException.badRequest(AuthService.WRONG_LOGIN)
        : new ApiException(HttpStatus.UNAUTHORIZED, ErrorResponse.SESSION_EXPIRED);
  }

  private static Long longClaim(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value instanceof String s) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
