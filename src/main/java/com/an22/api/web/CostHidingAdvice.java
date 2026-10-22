package com.an22.api.web;

import com.an22.api.auth.ShopSession;
import com.an22.api.rules.CostVisibility;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ซ่อนทุนจุดเดียว (E43, {@code hideCost()} ของ mock): ผู้ใช้ร้านที่ {@code canSeeCost()} = false ได้ทุก key ใน {@link CostVisibility#COST_FIELDS}
 * เป็น {@code null} ทุกระดับความลึก (ไม่ละ field — B24) และ object ยอดคงเหลือ {@code {qty, avgCost, value}} ได้ {@code value: null} ด้วย.
 */
@ControllerAdvice
public class CostHidingAdvice implements ResponseBodyAdvice<Object> {

  private final JsonMapper json;

  CostHidingAdvice(JsonMapper json) {
    this.json = json;
  }

  @Override
  public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
    if (body == null || body instanceof ErrorResponse || !(request instanceof ServletServerHttpRequest servlet)) {
      return body;
    }
    HttpServletRequest http = servlet.getServletRequest();
    if (!(http.getAttribute(ShopSession.ATTR) instanceof ShopSession session) || session.canSeeCost()) {
      return body;
    }
    JsonNode tree = json.valueToTree(body);
    strip(tree);
    return tree;
  }

  static void strip(JsonNode node) {
    if (node instanceof ArrayNode array) {
      array.forEach(CostHidingAdvice::strip);
    } else if (node instanceof ObjectNode object) {
      boolean balance = object.has("avgCost") && object.has("value");
      for (String name : List.copyOf(object.propertyNames())) {
        if (CostVisibility.COST_FIELDS.contains(name) || (balance && "value".equals(name))) {
          object.putNull(name);
        } else {
          strip(object.get(name));
        }
      }
    }
  }
}
