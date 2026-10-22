package com.an22.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/** JSON ของ api (เพิ่มจาก application.yml): BigDecimal เป็นตัวเลขธรรมดาเสมอ ({@code 100} ไม่ใช่ {@code 1E+2}). */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  @Bean
  JsonMapperBuilderCustomizer plainBigDecimal() {
    return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
  }
}
