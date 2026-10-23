package com.an22.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.an22.api.support.ApiTest;
import com.an22.api.support.TenantSql;
import com.an22.api.support.TenantSql.Actor;
import com.an22.api.support.TestCompanies;
import com.an22.api.support.TestCompanies.Company;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** เลขเอกสาร: พร้อมกันหลาย thread ไม่ซ้ำไม่ข้าม, rollback แล้วเลขถัดไปไม่ข้าม, แยกตามบริษัท. */
class DocNumbersIT extends ApiTest {

  @Autowired TestCompanies companies;
  @Autowired TenantSql sql;

  private String next(Company c, String prefix) {
    return sql.as(Actor.system(c.id()), jdbc -> new DocNumbers(jdbc).next(prefix, 4));
  }

  @Test
  void concurrentNumbersNeverRepeatOrSkip() throws Exception {
    Company c = companies.create();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(pool.submit(() -> next(c, "TR-HQ-20261023-")));
      }
      List<String> numbers = new ArrayList<>();
      for (Future<String> f : results) {
        numbers.add(f.get());
      }
      List<String> expected = new ArrayList<>();
      for (int i = 1; i <= 16; i++) {
        expected.add(String.format("TR-HQ-20261023-%04d", i));
      }
      assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void rolledBackNumbersAreReusedAndCountersArePerCompany() {
    Company a = companies.create();
    Company b = companies.create();
    assertThat(next(a, "X-")).isEqualTo("X-0001");
    sql.rolledBack(Actor.system(a.id()), jdbc -> assertThat(new DocNumbers(jdbc).next("X-", 4)).isEqualTo("X-0002"));
    assertThat(next(a, "X-")).isEqualTo("X-0002");
    assertThat(next(b, "X-")).isEqualTo("X-0001");
    String y = sql.as(Actor.system(a.id()), jdbc -> new DocNumbers(jdbc).next("Y-", 2));
    assertThat(y).isEqualTo("Y-01");
  }
}
