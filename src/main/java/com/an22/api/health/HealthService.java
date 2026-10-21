package com.an22.api.health;

import com.an22.api.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthService {

  private static final Logger log = LoggerFactory.getLogger(HealthService.class);

  private final HealthDao dao;

  HealthService(HealthDao dao) {
    this.dao = dao;
  }

  /** @throws ApiException 503 เมื่อต่อ DB ไม่ได้ */
  @Transactional(readOnly = true, timeout = 5)
  public void checkDatabase() {
    try {
      dao.ping();
    } catch (DataAccessException | CannotCreateTransactionException e) {
      log.warn("readyz: ต่อฐานข้อมูลไม่ได้: {}", e.getClass().getSimpleName());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ระบบยังไม่พร้อม กรุณาลองใหม่");
    }
  }
}
