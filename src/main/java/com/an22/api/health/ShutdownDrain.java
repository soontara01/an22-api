package com.an22.api.health;

import com.an22.api.config.ApiProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * ช่วง drain ก่อนปิด (E2E B7): ได้ SIGTERM → {@code /readyz} ตอบ 503 ทันที แต่ยังรับงานต่อ {@code SHUTDOWN_DRAIN} วิ ให้ probe ของ platform / lb
 * เลิกส่งงานใหม่ก่อน แล้วจึง graceful shutdown ของ web server (phase ต่ำกว่า = หยุดทีหลัง) — ไม่มีช่วงนี้ request ที่ lb ส่งมาตอน Tomcat
 * หยุดรับงานได้ connection reset → 502. grace period ของ platform ต้อง ≥ drain + 40 วิ.
 */
@Component
public class ShutdownDrain implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ShutdownDrain.class);

  private final Duration drain;
  private volatile boolean running;
  private volatile boolean draining;

  @Autowired
  ShutdownDrain(ApiProperties props) {
    this(Duration.ofSeconds(props.app().shutdownDrain()));
  }

  ShutdownDrain(Duration drain) {
    this.drain = drain;
  }

  /** กำลังจะปิด — {@code /readyz} ตอบ 503. */
  public boolean draining() {
    return draining;
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    draining = true;
    if (!drain.isZero()) {
      log.info("shutdown: /readyz 503 — รับงานต่อ {} วิ ก่อน graceful shutdown", drain.toSeconds());
      try {
        Thread.sleep(drain);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** หยุดก่อน graceful shutdown ของ web server (ลำดับหยุด: phase สูงก่อน). */
  @Override
  public int getPhase() {
    return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
  }
}
