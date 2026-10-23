package com.an22.api.billing;

import com.an22.api.db.ActorDao;
import com.an22.api.db.TxActor;
import com.an22.api.model.Subscription;
import com.an22.api.rules.PlanRules;
import com.an22.api.rules.SubscriptionRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * การเก็บเงิน SaaS (E35, แผนหัวข้อ "Stateless / scale"): ออกใบแจ้งหนี้รอบที่ถึง {@code nextInvoiceOn} (ราคาแพ็กเกจ ณ วันออก, ครบกำหนด +7 วัน,
 * เลข {@code SUB-YYYYMM-NNNN} ทั้งแพลตฟอร์ม) แล้วระงับบริษัทที่ค้างเกินผ่อนผัน ({@code refreshBilling()} ของ mock).
 * <p>{@link #catchUp} ใช้ทั้ง job รายวัน, login และ {@code GET /me}: ล็อกแถวบริษัท (ทำทีละ transaction) + unique (company, period_start) =
 * ออกซ้ำไม่ได้; เลขออกเฉพาะรอบที่ยังไม่มีใบ. ผู้ทำ = {@code system}.
 */
@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);
  /** เลขประจำล็อกของ job รายวัน ({@code pg_try_advisory_xact_lock}). */
  static final long DAILY_JOB_LOCK = 0x414E3232_00000001L;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  private final BillingDao dao;
  private final ActorDao actors;
  private final Clock clock;

  BillingService(BillingDao dao, ActorDao actors, Clock clock) {
    this.dao = dao;
    this.actors = actors;
    this.clock = clock;
  }

  /** บริษัทเดียว ใน transaction ของตัวเอง (login / me เรียกก่อนตรวจสถานะบริษัท) — คืน true เมื่อมีอะไรเปลี่ยน. */
  @Transactional(value = "platformTx", propagation = Propagation.REQUIRES_NEW)
  public boolean catchUp(long companyId) {
    return catchUpIn(companyId);
  }

  /** {@link #catchUp} ใน platformTx ที่เปิดอยู่แล้ว (เช่น ยกเลิกคำขอลบ — ให้ billing ตัดสินอีกครั้ง). */
  public boolean catchUpIn(long companyId) {
    actors.becomeOnPlatform(TxActor.SYSTEM);
    BillingDao.CompanyRow company = dao.lock(companyId).orElse(null);
    if (company == null) {
      return false;
    }
    LocalDate today = LocalDate.now(clock);
    SubscriptionRules.Periods due = SubscriptionRules.periodsToInvoice(company.nextInvoiceOn(), today);
    PlanRules.Plan plan = PlanRules.planOf(company.planId());
    for (SubscriptionRules.Period period : due.periods()) {
      if (dao.invoiced(companyId, period.start())) {
        continue;
      }
      String prefix = "SUB-" + period.start().format(MONTH) + "-";
      int n = dao.nextNumber(prefix);
      dao.insertInvoice(companyId, prefix + String.format("%04d", n), plan.id(), period.start(), period.end(),
          BigDecimal.valueOf(plan.monthlyPrice()), period.start().plusDays(SubscriptionRules.INVOICE_DUE_DAYS));
    }
    if (!due.periods().isEmpty()) {
      dao.setNextInvoiceOn(companyId, due.nextInvoiceOn());
    }
    SubscriptionRules.Status status = SubscriptionRules.subscriptionStatus(
        new Subscription(company.trialEndsOn(), due.nextInvoiceOn()), dao.invoices(companyId), today);
    if (status == SubscriptionRules.Status.SUSPENDED && company.active()) {
      dao.suspendOverdue(companyId);
      return true;
    }
    return !due.periods().isEmpty();
  }

  /**
   * job รายวัน: instance ที่ได้ล็อกทำทุกบริษัท (ตัวอื่นออกทันที) + ล้าง token / ตัวนับที่หมดอายุ — คืนจำนวนบริษัทที่เปลี่ยน (-1 = ไม่ได้ล็อก).
   */
  @Transactional("platformTx")
  public int runDaily() {
    if (!dao.tryJobLock(DAILY_JOB_LOCK)) {
      return -1;
    }
    int changed = 0;
    for (long id : dao.companyIds()) {
      if (catchUpIn(id)) {
        changed++;
      }
    }
    int cleaned = dao.cleanUp();
    log.info("billing job: บริษัทที่เปลี่ยน {} · ล้างของหมดอายุ {}", changed, cleaned);
    return changed;
  }
}
