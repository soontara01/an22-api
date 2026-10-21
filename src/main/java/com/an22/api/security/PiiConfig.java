package com.an22.api.security;

import com.an22.api.config.ApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PiiConfig {

  /** ไม่ตั้ง {@code PII_KEYS} / {@code PII_HASH_KEY} = ไม่ start. */
  @Bean
  PiiCipher piiCipher(ApiProperties props) {
    return PiiCipher.fromEnv(props.pii().keys(), props.pii().hashKey());
  }
}
