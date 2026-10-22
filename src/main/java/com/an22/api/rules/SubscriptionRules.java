package com.an22.api.rules;

import com.an22.api.model.BillingInvoice;
import com.an22.api.model.BillingNotice;
import com.an22.api.model.Subscription;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** port จาก {@code core/models/subscription.model.ts} (E35) — วันที่ = วันท้องถิ่น (LocalDate). */
public final class SubscriptionRules {

  public static final int TRIAL_DAYS = 14;
  public static final int INVOICE_DUE_DAYS = 7;
  public static final int GRACE_DAYS = 7;
  public static final int TRIAL_NOTICE_DAYS = 3;

  private SubscriptionRules() {}

  public enum Status {
    TRIAL,
    ACTIVE,
    PAST_DUE,
    SUSPENDED;

    /** ค่าใน JSON ({@code SubscriptionStatus} ของ TS). */
    public String json() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record Period(LocalDate start, LocalDate end) {}

  public record Periods(List<Period> periods, LocalDate nextInvoiceOn) {}

  /** วันเดียวกันของเดือนถัด ๆ ไป (เกินวันสุดท้ายของเดือน = วันสุดท้าย) — {@code plusMonths} ทำแบบเดียวกัน. */
  public static LocalDate addMonths(LocalDate date, int months) {
    return date.plusMonths(months);
  }

  /** รอบที่ถึงวันออกใบแจ้งหนี้แล้วภายใน {@code today} (ไม่เกิน 24 รอบ เก่าก่อน) + วันเริ่มรอบถัดไป. */
  public static Periods periodsToInvoice(LocalDate nextInvoiceOn, LocalDate today) {
    List<Period> periods = new ArrayList<>();
    LocalDate start = nextInvoiceOn;
    while (!start.isAfter(today) && periods.size() < 24) {
      LocalDate next = addMonths(start, 1);
      periods.add(new Period(start, next.minusDays(1)));
      start = next;
    }
    return new Periods(periods, start);
  }

  /** จำนวนวันจาก {@code from} ถึง {@code to} (ติดลบเมื่อ {@code to} มาก่อน). */
  public static long daysBetween(LocalDate from, LocalDate to) {
    return ChronoUnit.DAYS.between(from, to);
  }

  /** ใบที่ค้างชำระเก่าสุด (ตัดสินสถานะ) — null = ไม่มี. */
  public static BillingInvoice oldestOpenInvoice(List<BillingInvoice> invoices) {
    return invoices.stream()
        .filter(i -> "open".equals(i.status()))
        .min(Comparator.comparing(BillingInvoice::dueDate))
        .orElse(null);
  }

  /** วันสุดท้ายที่ยังใช้งานได้ทั้งที่ใบนี้ค้าง. */
  public static LocalDate suspendAfter(BillingInvoice invoice) {
    return invoice.dueDate().plusDays(GRACE_DAYS);
  }

  public static Status subscriptionStatus(Subscription sub, List<BillingInvoice> invoices, LocalDate today) {
    BillingInvoice open = oldestOpenInvoice(invoices);
    if (open != null) {
      if (!today.isAfter(open.dueDate())) {
        return Status.ACTIVE;
      }
      return !today.isAfter(suspendAfter(open)) ? Status.PAST_DUE : Status.SUSPENDED;
    }
    if (sub.trialEndsOn() != null && !today.isAfter(sub.trialEndsOn())) {
      return Status.TRIAL;
    }
    return Status.ACTIVE;
  }

  /** แถบแจ้งเตือนของ owner / admin: ทดลองใช้ใกล้หมด, ใบแจ้งหนี้รอชำระ, ค้างชำระ — null = ไม่มีอะไร. */
  public static BillingNotice billingNotice(Subscription sub, List<BillingInvoice> invoices, LocalDate today) {
    Status status = subscriptionStatus(sub, invoices, today);
    BillingInvoice open = oldestOpenInvoice(invoices);
    if (status == Status.TRIAL && sub.trialEndsOn() != null) {
      long left = daysBetween(today, sub.trialEndsOn());
      if (left > TRIAL_NOTICE_DAYS) {
        return null;
      }
      return new BillingNotice(
          "warn",
          "ทดลองใช้เหลืออีก " + left + " วัน (ถึง " + thaiDay(sub.trialEndsOn()) + ") — ใบแจ้งหนี้แรกออกวันที่ "
              + thaiDay(sub.nextInvoiceOn()));
    }
    if (open == null) {
      return null;
    }
    String amount = thaiNumber(open.amount());
    if (status == Status.PAST_DUE) {
      LocalDate last = suspendAfter(open);
      return new BillingNotice(
          "error",
          "ค้างชำระ " + open.invoiceNo() + " ฿" + amount + " (ครบกำหนด " + thaiDay(open.dueDate())
              + ") — จะระงับการใช้งานหลังวันที่ " + thaiDay(last) + " (อีก " + daysBetween(today, last) + " วัน)");
    }
    return new BillingNotice(
        "info", "ใบแจ้งหนี้ " + open.invoiceNo() + " ฿" + amount + " ครบกำหนดชำระ " + thaiDay(open.dueDate()));
  }

  /** {@code d/m/พ.ศ.} ของวันท้องถิ่น. */
  static String thaiDay(LocalDate date) {
    return date.getDayOfMonth() + "/" + date.getMonthValue() + "/" + (date.getYear() + 543);
  }

  /** {@code Number.toLocaleString('th-TH')}: คั่นหลักพันด้วย {@code ,} ทศนิยมไม่เกิน 3 ไม่เติม 0. */
  static String thaiNumber(BigDecimal value) {
    DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));
    return format.format(value);
  }
}
