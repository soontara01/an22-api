package com.an22.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import com.an22.api.config.ApiProperties;
import com.an22.api.config.ApiProperties.LogBody;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestLogFilterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Logger accessLogger = (Logger) LoggerFactory.getLogger("access");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    appender.start();
    accessLogger.addAppender(appender);
    accessLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detach() {
    accessLogger.detachAppender(appender);
  }

  private static RequestLogFilter filter(LogBody mode, int max) {
    ApiProperties props =
        new ApiProperties(
            new ApiProperties.Db(null, null, null, null, null, null, null, null, 1, true, List.of()),
            new ApiProperties.Pii(null, null),
            new ApiProperties.Jwt(null, 7),
            new ApiProperties.Log(mode, max),
            new ApiProperties.App(null, false));
    return new RequestLogFilter(props, JSON);
  }

  /** request JSON ไป {@code /api<path>} ที่ตอบ {@code responseBody} ด้วย {@code status}. */
  private Map<String, Object> run(LogBody mode, int max, String method, String path, String body, int status, String responseBody)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
    request.setContextPath("/api");
    request.addHeader("X-Real-IP", "203.0.113.7");
    if (body != null) {
      request.setContentType("application/json");
      request.setContent(body.getBytes(StandardCharsets.UTF_8));
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
              req.getInputStream().readAllBytes();
              var http = (jakarta.servlet.http.HttpServletResponse) res;
              http.setStatus(status);
              http.setContentType("application/json");
              http.getOutputStream().write(responseBody.getBytes(StandardCharsets.UTF_8));
            };
    filter(mode, max).doFilter(request, response, chain);
    assertThat(response.getContentAsString()).isEqualTo(responseBody);
    assertThat(appender.list).hasSize(1);
    return appender.list.getFirst().getKeyValuePairs().stream()
        .collect(Collectors.toMap(kv -> kv.key, kv -> kv.value == null ? "<null>" : kv.value));
  }

  @Test
  void accessLineHasAllFields() throws Exception {
    var fields = run(LogBody.ERROR, 4096, "POST", "/sales", "{\"a\":1}", 201, "{\"id\":1}");

    assertThat(fields)
        .containsEntry("type", "access")
        .containsEntry("method", "POST")
        .containsEntry("path", "/api/sales")
        .containsEntry("status", 201)
        .containsEntry("ip", "203.0.113.7")
        .containsEntry("tenantId", "<null>")
        .containsKeys("durationMs", "query", "userId", "branchId", "terminalId")
        .doesNotContainKeys("requestBody", "responseBody");
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
  }

  @Test
  void errorModeLogsBodyOnlyOnFailureAndMasksEveryLevel() throws Exception {
    String body =
        """
        {"name":"x","Password":"p1","items":[{"approval":{"username":"a","password":"p2"},"buyer":{"name":"B"}}],
         "deep":{"taxId":"123","address":"ที่อยู่","EMAIL":"a@b.c","reference":"4111111111111111","token":"t"}}
        """;
    var fields = run(LogBody.ERROR, 4096, "POST", "/sales", body, 400, "{\"message\":\"ผิด\"}");

    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    String logged = JSON.writeValueAsString(fields.get("requestBody"));
    assertThat(logged)
        .isEqualTo(
            "{\"name\":\"x\",\"Password\":\"***\",\"items\":[{\"approval\":\"***\",\"buyer\":\"***\"}],"
                + "\"deep\":{\"taxId\":\"***\",\"address\":\"***\",\"EMAIL\":\"***\",\"reference\":\"***\",\"token\":\"***\"}}");
    assertThat(fields.get("responseBody")).isEqualTo(Map.of("message", "ผิด"));
  }

  @Test
  void forbiddenPathsNeverLogBodyEvenInAllMode() throws Exception {
    for (String path :
        List.of("/auth/login", "/platform/auth/login", "/shifts/7/unlock", "/company/export", "/pos/held-bills", "/pos/held-bills/3")) {
      appender.list.clear();
      var fields = run(LogBody.ALL, 4096, "POST", path, "{\"x\":1}", 400, "{\"message\":\"m\"}");
      assertThat(fields).as(path).doesNotContainKeys("requestBody", "responseBody");
    }
    appender.list.clear();
    assertThat(run(LogBody.ALL, 4096, "POST", "/shifts/7/close", "{\"x\":1}", 200, "{}")).containsKey("requestBody");
  }

  @Test
  void longBodyIsTruncatedAfterMasking() throws Exception {
    String body = "{\"password\":\"secret-secret\",\"note\":\"" + "ก".repeat(400) + "\"}";
    var fields = run(LogBody.ALL, 256, "POST", "/sales", body, 200, "{}");

    assertThat(fields).containsEntry("truncated", true);
    String logged = (String) fields.get("requestBody");
    assertThat(logged.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
    assertThat(logged).startsWith("{\"password\":\"***\"").doesNotContain("secret");
  }

  @Test
  void healthIsDebug() throws Exception {
    run(LogBody.ERROR, 4096, "GET", "/healthz", null, 200, "{\"status\":\"ok\"}");
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @Test
  void serverErrorIsError() throws Exception {
    run(LogBody.ERROR, 4096, "GET", "/sales", null, 500, "{\"message\":\"m\"}");
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
  }

  @Test
  void queryValuesThatAreSecretAreMasked() {
    assertThat(RequestLogFilter.maskQuery("token=abc&from=2026-10-01&Code=9&password=x&taxId=1&flag"))
        .isEqualTo("token=***&from=2026-10-01&Code=***&password=***&taxId=***&flag");
    assertThat(RequestLogFilter.maskQuery(null)).isNull();
  }

  @Test
  void requestIdAcceptsSafeHeaderOnly() {
    assertThat(RequestLogFilter.requestId("abc-123_X.y")).isEqualTo("abc-123_X.y");
    assertThat(RequestLogFilter.requestId("bad id\n")).hasSize(36);
    assertThat(RequestLogFilter.requestId("x".repeat(65))).hasSize(36);
    assertThat(RequestLogFilter.requestId(null)).hasSize(36);
  }
}
