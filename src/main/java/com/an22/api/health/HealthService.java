package com.an22.api.health;

import com.an22.api.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HealthService {

  private static final Logger log = LoggerFactory.getLogger(HealthService.class);
  static final String NOT_READY = "ระบบยังไม่พร้อม กรุณาลองใหม่";

  private final HealthDao dao;
  private final ShutdownDrain drain;
  private final TransactionTemplate readOnly;

  /**
   * ใช้ {@link TransactionTemplate} แทน {@code @Transactional}: ขอ connection ตอนเปิด transaction — ถ้าเป็น annotation ความล้มเหลวเกิดก่อนเข้า method
   * ({@code try} จับไม่ได้ → 500 แทน 503, E2E B1).
   */
  HealthService(HealthDao dao, ShutdownDrain drain, PlatformTransactionManager appTx) {
    this.dao = dao;
    this.drain = drain;
    this.readOnly = new TransactionTemplate(appTx);
    this.readOnly.setReadOnly(true);
    this.readOnly.setTimeout(5);
  }

  /** @throws ApiException 503 เมื่อกำลังปิด ({@link ShutdownDrain}) หรือต่อ DB ไม่ได้ */
  public void checkDatabase() {
    if (drain.draining()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_READY);
    }
    try {
      readOnly.executeWithoutResult(s -> dao.ping());
    } catch (DataAccessException | TransactionException e) {
      log.warn("readyz: ต่อฐานข้อมูลไม่ได้: {}", e.getClass().getSimpleName());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_READY);
    }
  }
}
