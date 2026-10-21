package com.an22.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.an22.api.support.ApiTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** บรรทัด access เป็น JSON จริงบน stdout (structured logging) — ค่าเริ่ม LOG_BODY=error. */
@ExtendWith(OutputCaptureExtension.class)
class RequestLogIT extends ApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode accessLine(CapturedOutput output, String requestId) {
    List<JsonNode> lines =
        output.getOut().lines()
            .filter(line -> line.startsWith("{") && line.contains("\"type\":\"access\""))
            .map(JSON::readTree)
            .filter(node -> requestId.equals(node.path("requestId").asString()))
            .toList();
    assertThat(lines).as("access line of " + requestId).hasSize(1);
    return lines.getFirst();
  }

  @Test
  @WithMockUser
  void successLineHasFieldsButNoBody(CapturedOutput output) throws Exception {
    mvc.perform(
            post("/test-probe/echo")
                .queryParam("token", "abc")
                .queryParam("from", "2026-10-01")
                .header("X-Request-Id", "it-ok-1")
                .header("X-Real-IP", "203.0.113.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"secret\"}"))
        .andExpect(status().isOk());

    JsonNode line = accessLine(output, "it-ok-1");
    assertThat(line.path("level").asString()).isEqualTo("INFO");
    assertThat(line.path("method").asString()).isEqualTo("POST");
    assertThat(line.path("path").asString()).isEqualTo("/api/test-probe/echo");
    assertThat(line.path("query").asString()).isEqualTo("token=***&from=2026-10-01");
    assertThat(line.path("status").asInt()).isEqualTo(200);
    assertThat(line.has("durationMs")).isTrue();
    assertThat(line.path("ip").asString()).isEqualTo("203.0.113.7");
    assertThat(line.has("requestBody")).isFalse();
    assertThat(output.getOut()).doesNotContain("secret");
  }

  @Test
  @WithMockUser
  void failureLineHasMaskedBody(CapturedOutput output) throws Exception {
    mvc.perform(
            post("/test-probe/reject")
                .header("X-Request-Id", "it-fail-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"approval\":{\"username\":\"a\",\"password\":\"secret\"}}"))
        .andExpect(status().isBadRequest());

    JsonNode line = accessLine(output, "it-fail-1");
    assertThat(line.path("level").asString()).isEqualTo("WARN");
    assertThat(line.path("requestBody").toString()).isEqualTo("{\"name\":\"n\",\"approval\":\"***\"}");
    assertThat(line.path("responseBody").path("message").asString()).isEqualTo("ปฏิเสธตามกฎ");
    assertThat(output.getOut()).doesNotContain("secret");
  }
}
