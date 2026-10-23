package com.an22.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.config.ClockConfig;
import com.an22.api.db.ActorDao;
import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** การเก็บเงิน (E35): ออกใบตามรอบ / ระงับเมื่อพ้นผ่อนผัน (Clock ปลอม), job พร้อมกัน + login ก่อน job ไม่ออกซ้ำ. */
class BillingIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;
  @Autowired BillingService billing;
  @Autowired BillingDao dao;
  @Autowired ActorDao actors;
  @Autowired @Qualifier("platformTx") PlatformTransactionManager platformTx;

  private void schedule(Company c, LocalDate trialEndsOn, LocalDate nextInvoiceOn) {
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET trial_ends_on = :trial, next_invoice_on = :next, plan_id = 'starter' WHERE id = :id")
        .param("trial", trialEndsOn).param("next", nextInvoiceOn).param("id", c.id()).update());
  }

  private List<String> invoices(Company c) {
    return sql.platform(jdbc -> jdbc.sql("SELECT invoice_no || '|' || period_start || '|' || period_end || '|' || amount || '|' || due_date"
            + " || '|' || created_by_kind FROM dt_billing_invoices WHERE company_id = :c ORDER BY period_start")
        .param("c", c.id()).query(String.class).list());
  }

  private String state(Company c) {
    return sql.platform(jdbc -> jdbc.sql("SELECT active || '|' || coalesce(suspended_reason, '-') || '|' || next_invoice_on"
            + " FROM dt_companies WHERE id = :id").param("id", c.id()).query(String.class).single());
  }

  /** {@link BillingService} ที่วันนี้ = {@code today} (ไม่ผ่าน proxy — เปิด platformTx เอง). */
  private boolean catchUpOn(Company c, String today) {
    Clock clock = Clock.fixed(LocalDate.parse(today).atStartOfDay(ClockConfig.BANGKOK).plusHours(9).toInstant(), ClockConfig.BANGKOK);
    BillingService on = new BillingService(dao, actors, clock);
    return new TransactionTemplate(platformTx).execute(s -> on.catchUpIn(c.id()));
  }

  @Test
  void invoicesEachPeriodReachedAndSuspendsAfterTheGraceDays() {
    Company c = companies.create();
    schedule(c, LocalDate.parse("2031-01-14"), LocalDate.parse("2031-01-15"));

    assertThat(catchUpOn(c, "2031-01-14")).isFalse();
    assertThat(invoices(c)).isEmpty();
    assertThat(catchUpOn(c, "2031-01-15")).isTrue();
    assertThat(invoices(c)).hasSize(1);
    assertThat(invoices(c).getFirst()).matches("SUB-203101-\\d{4}\\|2031-01-15\\|2031-02-14\\|590\\.00\\|2031-01-22\\|system");
    assertThat(state(c)).isEqualTo("true|-|2031-02-15");
    assertThat(catchUpOn(c, "2031-01-20")).isFalse();
    // ค้างเกินกำหนด + ผ่อนผัน 7 วัน (ครบกำหนด 22 → ระงับตั้งแต่ 30)
    assertThat(catchUpOn(c, "2031-01-29")).isFalse();
    assertThat(state(c)).startsWith("true|");
    assertThat(catchUpOn(c, "2031-01-30")).isTrue();
    assertThat(state(c)).isEqualTo("false|overdue|2031-02-15");
    // ข้ามหลายรอบ: ออกทุกรอบที่ถึง (ราคาแพ็กเกจ ณ วันออก)
    sql.platform(jdbc -> jdbc.sql("UPDATE dt_companies SET plan_id = 'standard' WHERE id = :id").param("id", c.id()).update());
    assertThat(catchUpOn(c, "2031-03-20")).isTrue();
    assertThat(invoices(c)).hasSize(3);
    assertThat(invoices(c).get(1)).matches("SUB-203102-\\d{4}\\|2031-02-15\\|2031-03-14\\|1490\\.00\\|2031-02-22\\|system");
    assertThat(invoices(c).get(2)).startsWith("SUB-203103-");
    assertThat(state(c)).isEqualTo("false|overdue|2031-04-15");
  }

  @Test
  void twoJobsAtOnceAndLoginBeforeTheJobIssueOneInvoice() throws Exception {
    LocalDate today = LocalDate.now(ClockConfig.BANGKOK);
    Company viaLogin = companies.create();
    schedule(viaLogin, today.minusDays(1), today);
    login(viaLogin.code(), "owner", TestCompanies.PASSWORD);
    assertThat(invoices(viaLogin)).hasSize(1);

    Company viaJob = companies.create();
    schedule(viaJob, today.minusDays(1), today);
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Integer>> jobs = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
      try {
        start.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return billing.runDaily();
    })).toList();
    start.countDown();
    for (CompletableFuture<Integer> job : jobs) {
      job.get();
    }
    assertThat(invoices(viaJob)).hasSize(1);
    assertThat(invoices(viaLogin)).hasSize(1);
    assertThat(state(viaJob)).isEqualTo("true|-|" + today.plusMonths(1));
  }
}
