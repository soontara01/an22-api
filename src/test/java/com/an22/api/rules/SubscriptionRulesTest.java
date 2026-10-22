package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.model.BillingInvoice;
import com.an22.api.model.BillingNotice;
import com.an22.api.model.Subscription;
import com.an22.api.rules.SubscriptionRules.Period;
import com.an22.api.rules.SubscriptionRules.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ตาม {@code subscription.model.spec.ts} — ข้อความตรงทุกตัวอักษร. */
class SubscriptionRulesTest {

  private static LocalDate d(String iso) {
    return LocalDate.parse(iso);
  }

  private static BillingInvoice invoice(String dueDate, String status) {
    return new BillingInvoice(1, 1, "SUB-202610-0001", "starter", d("2026-10-01"), d("2026-10-31"),
        new BigDecimal("590.00"), d("2026-10-01"), d(dueDate), status, null);
  }

  private final Subscription sub = new Subscription(d("2026-10-14"), d("2026-10-15"));

  @Test
  void addsMonthsKeepingTheDayClampedToTheMonthEnd() {
    assertThat(SubscriptionRules.addMonths(d("2026-01-31"), 1)).isEqualTo(d("2026-02-28"));
    assertThat(SubscriptionRules.addMonths(d("2026-10-15"), 3)).isEqualTo(d("2027-01-15"));
    assertThat(SubscriptionRules.daysBetween(d("2026-10-18"), d("2026-10-21"))).isEqualTo(3);
  }

  @Test
  void listsTheMonthlyPeriodsReachedByToday() {
    var result = SubscriptionRules.periodsToInvoice(d("2026-09-15"), d("2026-10-18"));
    assertThat(result.periods())
        .containsExactly(new Period(d("2026-09-15"), d("2026-10-14")), new Period(d("2026-10-15"), d("2026-11-14")));
    assertThat(result.nextInvoiceOn()).isEqualTo(d("2026-11-15"));
    assertThat(SubscriptionRules.periodsToInvoice(d("2026-11-01"), d("2026-10-18")).periods()).isEmpty();
  }

  @Test
  void goesTrialActivePastDueSuspendedAndBackOncePaid() {
    assertThat(SubscriptionRules.subscriptionStatus(sub, List.of(), d("2026-10-14"))).isEqualTo(Status.TRIAL);
    assertThat(SubscriptionRules.subscriptionStatus(sub, List.of(), d("2026-10-15"))).isEqualTo(Status.ACTIVE);
    List<BillingInvoice> open = List.of(invoice("2026-10-22", "open"));
    assertThat(SubscriptionRules.subscriptionStatus(sub, open, d("2026-10-22"))).isEqualTo(Status.ACTIVE);
    assertThat(SubscriptionRules.subscriptionStatus(sub, open, d("2026-10-23"))).isEqualTo(Status.PAST_DUE);
    assertThat(SubscriptionRules.subscriptionStatus(sub, open, d("2026-10-29"))).isEqualTo(Status.PAST_DUE);
    assertThat(SubscriptionRules.subscriptionStatus(sub, open, d("2026-10-30"))).isEqualTo(Status.SUSPENDED);
    assertThat(SubscriptionRules.subscriptionStatus(sub, List.of(invoice("2026-10-22", "paid")), d("2026-10-30")))
        .isEqualTo(Status.ACTIVE);
    assertThat(Status.PAST_DUE.json()).isEqualTo("past_due");
  }

  @Test
  void tellsTheOwnerAboutTrialInvoiceOrOverdue() {
    assertThat(SubscriptionRules.billingNotice(sub, List.of(), d("2026-10-10"))).isNull();
    assertThat(SubscriptionRules.billingNotice(sub, List.of(), d("2026-10-12")))
        .isEqualTo(new BillingNotice("warn", "ทดลองใช้เหลืออีก 2 วัน (ถึง 14/10/2569) — ใบแจ้งหนี้แรกออกวันที่ 15/10/2569"));
    List<BillingInvoice> open = List.of(invoice("2026-10-22", "open"));
    assertThat(SubscriptionRules.billingNotice(sub, open, d("2026-10-20")).level()).isEqualTo("info");
    assertThat(SubscriptionRules.billingNotice(sub, open, d("2026-10-25")))
        .isEqualTo(new BillingNotice("error",
            "ค้างชำระ SUB-202610-0001 ฿590 (ครบกำหนด 22/10/2569) — จะระงับการใช้งานหลังวันที่ 29/10/2569 (อีก 4 วัน)"));
    assertThat(SubscriptionRules.billingNotice(sub, List.of(invoice("2026-10-22", "paid")), d("2026-10-25"))).isNull();
  }

  @Test
  void amountsLikeToLocaleStringTh() {
    assertThat(SubscriptionRules.thaiNumber(new BigDecimal("3490.00"))).isEqualTo("3,490");
    assertThat(SubscriptionRules.thaiNumber(new BigDecimal("1234567.50"))).isEqualTo("1,234,567.5");
  }
}
