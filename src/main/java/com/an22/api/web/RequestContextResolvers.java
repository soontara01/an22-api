package com.an22.api.web;

import com.an22.api.auth.ShopSession;
import com.an22.api.model.PlatformAdmin;
import com.an22.api.security.PlatformSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * controller รับค่าที่ filter ของ session ตรวจแล้วเป็น parameter ได้: {@link ShopSession}, {@link BranchContext},
 * {@link TerminalContext}, {@link PlatformAdmin} — ไม่มีใน request (เรียกผิด chain) → 401.
 */
@Configuration(proxyBeanMethods = false)
public class RequestContextResolvers implements WebMvcConfigurer {

  private static final Map<Class<?>, String> ATTRS =
      Map.of(
          ShopSession.class, ShopSession.ATTR,
          BranchContext.class, BranchContext.ATTR,
          TerminalContext.class, TerminalContext.ATTR,
          PlatformAdmin.class, PlatformSessionFilter.ATTR);

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(
        new HandlerMethodArgumentResolver() {
          @Override
          public boolean supportsParameter(MethodParameter parameter) {
            return ATTRS.containsKey(parameter.getParameterType());
          }

          @Override
          public Object resolveArgument(
              MethodParameter parameter,
              ModelAndViewContainer mav,
              NativeWebRequest request,
              WebDataBinderFactory binder) {
            Object value =
                request.getNativeRequest(HttpServletRequest.class).getAttribute(ATTRS.get(parameter.getParameterType()));
            if (value == null) {
              throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorResponse.SESSION_EXPIRED);
            }
            return value;
          }
        });
  }
}
