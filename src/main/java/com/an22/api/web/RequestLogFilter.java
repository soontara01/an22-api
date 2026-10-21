package com.an22.api.web;

import com.an22.api.config.ApiProperties;
import com.an22.api.config.ApiProperties.LogBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * filter แรกสุด: log 1 บรรทัด JSON ต่อ request หลังตอบเสร็จ ({@code "type":"access"}) + request id.
 *
 * <ul>
 *   <li>body ตาม {@code LOG_BODY} (off / error = เฉพาะ 4xx-5xx / all), เฉพาะ JSON, mask key อ่อนไหวทุกระดับก่อน log,
 *       ตัดที่ {@code LOG_BODY_MAX} + {@code "truncated": true}
 *   <li>path ใน {@link #NO_BODY_PATHS} ไม่ log body เลย (แม้ {@code all}); ไม่ log header (มี Authorization)
 *   <li>ระดับ: 5xx error, 4xx warn, อื่น info, health = debug
 * </ul>
 *
 * ขั้น 2 ตั้ง tenant / user / สาขา / เครื่องเป็น request attribute ({@code ATTR_*}) ให้ log อ่าน.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

  public static final String ATTR_TENANT_ID = "an22.log.tenantId";
  public static final String ATTR_USER_ID = "an22.log.userId";
  public static final String ATTR_BRANCH_ID = "an22.log.branchId";
  public static final String ATTR_TERMINAL_ID = "an22.log.terminalId";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String MDC_REQUEST_ID = "requestId";

  /** ชื่อ key ที่ mask เป็น "***" (ไม่สนตัวพิมพ์) — buyer ทั้งก้อน (PII), reference อาจเป็นเลขบัตร. */
  static final Set<String> MASKED_KEYS =
      Set.of("password", "token", "approval", "buyer", "taxid", "address", "email", "reference");

  /** ค่าใน query ที่ mask (แบบเดียวกับ an22-web). */
  static final Set<String> MASKED_QUERY = Set.of("token", "code", "password", "taxid");

  /** path (ไม่รวม /api) ที่ไม่ log body เลย. */
  static final List<String> NO_BODY_PATHS =
      List.of(
          "/auth/**",
          "/platform/auth/**",
          "/shifts/*/unlock",
          "/company/export",
          "/pos/held-bills",
          "/pos/held-bills/**");

  private static final Set<String> HEALTH_PATHS = Set.of("/healthz", "/readyz");
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  /** อ่าน body ไว้ log ได้ไม่เกินนี้ (เกิน = ไม่ log body — mask ข้อความที่ถูกตัดไม่ได้). */
  private static final int CACHE_LIMIT = 1024 * 1024;

  private static final Logger log = LoggerFactory.getLogger("access");
  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  private final LogBody bodyMode;
  private final int bodyMax;
  private final JsonMapper json;

  public RequestLogFilter(ApiProperties props, JsonMapper json) {
    this.bodyMode = props.log().body();
    this.bodyMax = props.log().bodyMax();
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
    MDC.put(MDC_REQUEST_ID, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);

    String apiPath = ErrorHandler.apiPath(request);
    boolean capture = bodyMode != LogBody.OFF && bodyAllowed(apiPath);
    ContentCachingRequestWrapper cachedRequest =
        capture ? new ContentCachingRequestWrapper(request, CACHE_LIMIT) : null;
    ContentCachingResponseWrapper cachedResponse =
        capture ? new ContentCachingResponseWrapper(response) : null;
    boolean failed = false;
    try {
      chain.doFilter(
          cachedRequest != null ? cachedRequest : request,
          cachedResponse != null ? cachedResponse : response);
    } catch (IOException | ServletException | RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      int status = failed ? 500 : response.getStatus();
      try {
        writeAccessLog(request, apiPath, status, start, cachedRequest, cachedResponse);
      } catch (RuntimeException e) {
        log.warn("เขียน access log ไม่สำเร็จ: {}", e.getClass().getSimpleName());
      }
      if (cachedResponse != null) {
        cachedResponse.copyBodyToResponse();
      }
      MDC.remove(MDC_REQUEST_ID);
    }
  }

  private void writeAccessLog(
      HttpServletRequest request,
      String apiPath,
      int status,
      long start,
      ContentCachingRequestWrapper cachedRequest,
      ContentCachingResponseWrapper cachedResponse) {
    Level level =
        HEALTH_PATHS.contains(apiPath)
            ? Level.DEBUG
            : status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
    if (!log.isEnabledForLevel(level)) {
      return;
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("type", "access");
    fields.put("method", request.getMethod());
    fields.put("path", request.getRequestURI());
    fields.put("query", maskQuery(request.getQueryString()));
    fields.put("status", status);
    fields.put("durationMs", (System.nanoTime() - start) / 1_000_000);
    fields.put("tenantId", request.getAttribute(ATTR_TENANT_ID));
    fields.put("userId", request.getAttribute(ATTR_USER_ID));
    fields.put("branchId", request.getAttribute(ATTR_BRANCH_ID));
    fields.put("terminalId", request.getAttribute(ATTR_TERMINAL_ID));
    fields.put("ip", clientIp(request));
    boolean withBody = cachedRequest != null && (bodyMode == LogBody.ALL || status >= 400);
    if (withBody) {
      Body req = body(cachedRequest.getContentType(), cachedRequest.getContentAsByteArray(), cachedRequest.getContentLength());
      Body res = body(cachedResponse.getContentType(), cachedResponse.getContentAsByteArray(), -1);
      fields.put("requestBody", req.value());
      fields.put("responseBody", res.value());
      if (req.truncated() || res.truncated()) {
        fields.put("truncated", true);
      }
    }
    LoggingEventBuilder event = log.atLevel(level);
    fields.forEach(event::addKeyValue);
    event.log("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, fields.get("durationMs"));
  }

  private record Body(Object value, boolean truncated) {}

  /** body ที่ mask แล้ว (Map / List / ค่าเดี่ยว) — ยาวเกิน {@code LOG_BODY_MAX} = ข้อความ JSON ที่ถูกตัด. */
  Body body(String contentType, byte[] content, int declaredLength) {
    if (content.length == 0) {
      return new Body(null, false);
    }
    if (!isJson(contentType)) {
      return new Body("(ไม่ใช่ JSON " + content.length + " byte)", false);
    }
    if (content.length >= CACHE_LIMIT || declaredLength > content.length) {
      return new Body(null, true);
    }
    JsonNode node;
    try {
      node = json.readTree(content);
    } catch (JacksonException e) {
      return new Body("(JSON อ่านไม่ได้ " + content.length + " byte)", false);
    }
    mask(node);
    String text = json.writeValueAsString(node);
    if (text.getBytes(StandardCharsets.UTF_8).length > bodyMax) {
      return new Body(truncate(text, bodyMax), true);
    }
    return new Body(json.convertValue(node, Object.class), false);
  }

  /** แทนค่าของ key ใน {@link #MASKED_KEYS} ด้วย "***" ทุกระดับความลึก. */
  static void mask(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String name : new ArrayList<>(object.propertyNames())) {
        if (MASKED_KEYS.contains(name.toLowerCase(Locale.ROOT))) {
          object.put(name, "***");
        } else {
          mask(object.get(name));
        }
      }
    } else if (node instanceof ArrayNode array) {
      array.forEach(RequestLogFilter::mask);
    }
  }

  static String maskQuery(String query) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (String part : query.split("&", -1)) {
      if (!out.isEmpty()) {
        out.append('&');
      }
      int eq = part.indexOf('=');
      String name = eq < 0 ? part : part.substring(0, eq);
      if (eq >= 0 && MASKED_QUERY.contains(name.toLowerCase(Locale.ROOT))) {
        out.append(name).append("=***");
      } else {
        out.append(part);
      }
    }
    return out.toString();
  }

  static boolean bodyAllowed(String apiPath) {
    return NO_BODY_PATHS.stream().noneMatch(pattern -> MATCHER.match(pattern, apiPath));
  }

  static String requestId(String header) {
    return header != null && REQUEST_ID.matcher(header).matches() ? header : UUID.randomUUID().toString();
  }

  /** IP ผู้ใช้ = X-Real-IP ที่ an22-web ใส่ (api เข้าถึงได้จาก web เท่านั้น — E49). */
  private static String clientIp(HttpServletRequest request) {
    String real = request.getHeader("X-Real-IP");
    return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) {
      return false;
    }
    try {
      MediaType type = MediaType.parseMediaType(contentType);
      return MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** ตัดตาม byte โดยไม่ผ่ากลางตัวอักษร UTF-8. */
  private static String truncate(String text, int maxBytes) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    int end = maxBytes;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
      end--;
    }
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }
}
